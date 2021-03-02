package no.nav.helse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.*
import io.ktor.auth.Authentication
import io.ktor.auth.authenticate
import io.ktor.features.CallId
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.jackson.jackson
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.routing.Routing
import io.ktor.util.AttributeKey
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.auth.AccessTokenClientResolver
import no.nav.helse.dokument.DokumentGateway
import no.nav.helse.dusseldorf.ktor.auth.AuthStatusPages
import no.nav.helse.dusseldorf.ktor.auth.allIssuers
import no.nav.helse.dusseldorf.ktor.auth.clients
import no.nav.helse.dusseldorf.ktor.auth.multipleJwtIssuers
import no.nav.helse.dusseldorf.ktor.core.*
import no.nav.helse.dusseldorf.ktor.health.HealthRoute
import no.nav.helse.dusseldorf.ktor.health.HealthService
import no.nav.helse.dusseldorf.ktor.jackson.JacksonStatusPages
import no.nav.helse.dusseldorf.ktor.jackson.dusseldorfConfigured
import no.nav.helse.dusseldorf.ktor.metrics.MetricsRoute
import no.nav.helse.dusseldorf.ktor.metrics.init
import no.nav.helse.mottak.v1.arbeidstaker.SoknadKafkaProducer
import no.nav.helse.mottak.v1.arbeidstaker.SoknadMottakService
import no.nav.helse.mottak.v1.arbeidstaker.SøknadApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("no.nav.OmsorgspengerutbetalingsoknadMottak")
private const val soknadIdKey = "soknad_id"
private val soknadIdAttributeKey = AttributeKey<String>(soknadIdKey)

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@KtorExperimentalAPI
fun Application.omsorgspengerutbetalingsoknadMottak() {
    val appId = environment.config.id()
    logProxyProperties()
    DefaultExports.initialize()

    val configuration = Configuration(environment.config)
    val issuers = configuration.issuers()
    val accessTokenClientResolver = AccessTokenClientResolver(environment.config.clients())

    install(Authentication) {
        multipleJwtIssuers(issuers)
    }

    install(ContentNegotiation) {
        jackson {
            dusseldorfConfigured()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        }
    }

    install(StatusPages) {
        DefaultStatusPages()
        JacksonStatusPages()
        AuthStatusPages()
    }

    install(CallIdRequired)

    install(MicrometerMetrics) {
        init(appId)
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        call.request.log()
    }

    install(CallId) {
        fromXCorrelationIdHeader()
    }

    install(CallLogging) {
        correlationIdAndRequestIdInMdc()
        logRequests()
        mdc(soknadIdKey) { it.setSoknadItAsAttributeAndGet() }
    }

    val kafkaProducer = SoknadKafkaProducer(
        kafkaConfig = configuration.getKafkaConfig()
    )

    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Stopper Kafka Producer.")
        kafkaProducer.stop()
        logger.info("Kafka Producer Stoppet.")
    }

    val dokumentGateway = DokumentGateway(
        accessTokenClient = accessTokenClientResolver.dokumentAccessTokenClient(),
        baseUrl = configuration.getK9DokumentBaseUrl(),
        lagreDokumentScopes = configuration.getLagreDokumentScopes()
    )

    install(Routing) {
        HealthRoute(
            healthService = HealthService(
                healthChecks = setOf(
                    kafkaProducer,
                    dokumentGateway
                )
            )
        )
        MetricsRoute()
        DefaultProbeRoutes()
        authenticate(*issuers.allIssuers()) {
            requiresCallId {
                SøknadApi(
                    soknadV1MottakService = SoknadMottakService(
                        dokumentGateway = dokumentGateway,
                        soknadV1KafkaProducer = kafkaProducer
                    )
                )
            }
        }
    }
}

internal fun k9DokumentKonfigurert(): ObjectMapper = jacksonObjectMapper().dusseldorfConfigured().apply {
    propertyNamingStrategy = PropertyNamingStrategy.SNAKE_CASE
}

private fun ApplicationCall.setSoknadItAsAttributeAndGet(): String {
    val soknadId = UUID.randomUUID().toString()
    attributes.put(soknadIdAttributeKey, soknadId)
    return soknadId
}

internal fun ApplicationCall.getSoknadId() = SoknadId(attributes[soknadIdAttributeKey])
