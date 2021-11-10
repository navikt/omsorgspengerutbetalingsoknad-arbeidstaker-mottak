package no.nav.helse

import com.github.tomakehurst.wiremock.WireMockServer
import com.typesafe.config.ConfigFactory
import io.ktor.config.ApplicationConfig
import io.ktor.config.HoconApplicationConfig
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.stop
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import no.nav.common.KafkaEnvironment
import no.nav.helse.RequestUtils.requestAndAssert
import no.nav.helse.dusseldorf.testsupport.jws.Azure
import no.nav.helse.dusseldorf.testsupport.wiremock.WireMockBuilder
import no.nav.helse.kafka.Topics
import no.nav.helse.mottak.v1.JsonKeys
import no.nav.helse.mottak.v1.arbeidstaker.SoknadIncoming
import no.nav.helse.mottak.v1.arbeidstaker.SoknadOutgoing
import org.json.JSONObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.skyscreamer.jsonassert.JSONAssert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SoknadMottakTest {

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(SoknadMottakTest::class.java)

        // Se https://github.com/navikt/dusseldorf-ktor#f%C3%B8dselsnummer
        private val gyldigFodselsnummerA = "02119970078"
        private val gyldigFodselsnummerB = "19066672169"
        private val gyldigFodselsnummerC = "20037473937"
        private val dNummerA = "55125314561"

        private val wireMockServer: WireMockServer = WireMockBuilder()
            .withAzureSupport()
            .build()
            .stubK9DokumentHealth()
            .stubLagreDokument()
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerA, "1234561")
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerB, "1234562")
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerC, "1234563")
            .stubAktoerRegisterGetAktoerId(dNummerA, "1234564")


        private val kafkaEnvironment = KafkaWrapper.bootstrap()
        private val kafkaTestConsumer = kafkaEnvironment.testConsumer()

        private val authorizedAccessToken = Azure.V1_0.generateJwt(
            clientId = "omsorgspengerutbetalingsoknad-arbeidstaker-api",
            audience = "omsorgspengerutbetalingsoknad-arbeidstaker-mottak"
        )
        private val unAauthorizedAccessToken = Azure.V2_0.generateJwt(
            clientId = "ikke-authorized-client",
            audience = "omsorgspengerutbetalingsoknad-arbeidstaker-mottak",
            accessAsApplication = false
        )

        private var engine = newEngine(kafkaEnvironment)

        private fun getConfig(kafkaEnvironment: KafkaEnvironment): ApplicationConfig {
            val fileConfig = ConfigFactory.load()
            val testConfig = ConfigFactory.parseMap(
                TestConfiguration.asMap(
                    wireMockServer = wireMockServer,
                    kafkaEnvironment = kafkaEnvironment,
                    omsorgspengerutbetalingsoknadMottakAzureClientId = "omsorgspengerutbetalingsoknad-arbeidstaker-mottak"
                )
            )
            val mergedConfig = testConfig.withFallback(fileConfig)
            return HoconApplicationConfig(mergedConfig)
        }

        private fun newEngine(kafkaEnvironment: KafkaEnvironment) = TestApplicationEngine(createTestEnvironment {
            config = getConfig(kafkaEnvironment)
        })

        @BeforeAll
        @JvmStatic
        fun buildUp() {
            logger.info("Building up")
            engine.start(wait = true)
            logger.info("Buildup complete")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            logger.info("Tearing down")
            wireMockServer.stop()
            engine.stop(5, 60, TimeUnit.SECONDS)
            kafkaEnvironment.tearDown()
            logger.info("Tear down complete")
        }
    }

    @Test
    fun `test isready, isalive, health og metrics`() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/isready") {}.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                handleRequest(HttpMethod.Get, "/isalive") {}.apply {
                    assertEquals(HttpStatusCode.OK, response.status())
                    handleRequest(HttpMethod.Get, "/metrics") {}.apply {
                        assertEquals(HttpStatusCode.OK, response.status())
                        handleRequest(HttpMethod.Get, "/health") {}.apply {
                            assertEquals(HttpStatusCode.OK, response.status())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `Gyldig søknad blir lagt til prosessering`() {
        gyldigSoknadBlirLagtTilProsessering(
            Azure.V1_0.generateJwt(
                clientId = "omsorgspengerutbetalingsoknad-arbeidstaker-api",
                audience = "omsorgspengerutbetalingsoknad-arbeidstaker-mottak"
            )
        )

        gyldigSoknadBlirLagtTilProsessering(
            Azure.V2_0.generateJwt(
                clientId = "omsorgspengerutbetalingsoknad-arbeidstaker-api",
                audience = "omsorgspengerutbetalingsoknad-arbeidstaker-mottak"
            )
        )
    }

    @Test
    fun `Gyldig søknad med søknadId fra API blir lagt til prosessering`(){
        val søknadId = UUID.randomUUID().toString()
        val søknad = gyldigSoknad(gyldigFodselsnummerA, søknadId)

        requestAndAssert(
            soknad = søknad,
            expectedCode = HttpStatusCode.Accepted,
            expectedResponse = """{"id":"$søknadId"}""",
            accessToken = authorizedAccessToken,
            path = "/v1/soknad",
            logger = logger,
            kafkaEngine = engine
        )

    }


    private fun gyldigSoknadBlirLagtTilProsessering(accessToken: String) {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        val soknadId = requestAndAssert(
            soknad = soknad,
            expectedResponse = null,
            expectedCode = HttpStatusCode.Accepted,
            accessToken = accessToken,
            path = "/v1/soknad",
            logger = logger,
            kafkaEngine = engine
        )

        val sendtTilProsessering = hentSoknadSendtTilProsessering(soknadId)
        verifiserSoknadLagtTilProsessering(
            incomingJsonString = soknad,
            outgoingJsonObject = sendtTilProsessering
        )
    }

    @Test
    fun `Gyldig søknad fra D-nummer blir lagt til prosessering`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = dNummerA
        )

        val soknadId = requestAndAssert(
            soknad = soknad,
            expectedResponse = null,
            expectedCode = HttpStatusCode.Accepted,
            path = "/v1/soknad",
            accessToken = authorizedAccessToken,
            logger = logger,
            kafkaEngine = engine
        )

        val sendtTilProsessering = hentSoknadSendtTilProsessering(soknadId)
        verifiserSoknadLagtTilProsessering(
            incomingJsonString = soknad,
            outgoingJsonObject = sendtTilProsessering
        )
    }

    @Test
    fun `Request fra ikke autorisert system feiler`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        requestAndAssert(
            soknad = soknad,
            expectedResponse = """
            {
                "type": "/problem-details/unauthorized",
                "title": "unauthorized",
                "status": 403,
                "detail": "Requesten inneholder ikke tilstrekkelige tilganger.",
                "instance": "about:blank"
            }
            """.trimIndent(),
            expectedCode = HttpStatusCode.Forbidden,
            accessToken = unAauthorizedAccessToken,
            path = "/v1/soknad",
            logger = logger,
            kafkaEngine = engine
        )
    }

    @Test
    fun `Request uten corelation id feiler`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        requestAndAssert(
            soknad = soknad,
            expectedResponse = """
                {
                    "type": "/problem-details/invalid-request-parameters",
                    "title": "invalid-request-parameters",
                    "detail": "Requesten inneholder ugyldige paramtere.",
                    "status": 400,
                    "instance": "about:blank",
                    "invalid_parameters" : [
                        {
                            "name" : "X-Correlation-ID",
                            "reason" : "Correlation ID må settes.",
                            "type": "header"
                        }
                    ]
                }
            """.trimIndent(),
            expectedCode = HttpStatusCode.BadRequest,
            leggTilCorrelationId = false,
            path = "/v1/soknad",
            accessToken = authorizedAccessToken,
            logger = logger,
            kafkaEngine = engine
        )
    }

    @Test
    fun `En ugyldig melding gir valideringsfeil`() {
        val soknad = """
        {
            "søker": {
                "aktørId": "ABC",
                "fødselsnummer": "02119970078"
            },
            vedlegg: []
        }
        """.trimIndent()

        requestAndAssert(
            soknad = soknad,
            expectedResponse = """
                {
                    "type": "/problem-details/invalid-request-parameters",
                    "title": "invalid-request-parameters",
                    "status": 400,
                    "detail": "Requesten inneholder ugyldige paramtere.",
                    "instance": "about:blank",
                    "invalid_parameters": [{
                        "type": "entity",
                        "name": "søker.aktørId",
                        "reason": "Ikke gyldig Aktør ID.",
                        "invalid_value": "ABC"
                    }]
                }
            """.trimIndent(),
            expectedCode = HttpStatusCode.BadRequest,
            path = "/v1/soknad",
            accessToken = authorizedAccessToken,
            logger = logger,
            kafkaEngine = engine
        )
    }

    // Utils
    private fun verifiserSoknadLagtTilProsessering(
        incomingJsonString: String,
        outgoingJsonObject: JSONObject
    ) {
        val outgoing = SoknadOutgoing(outgoingJsonObject)

        val outgoingFromIncoming = SoknadIncoming(incomingJsonString)
            .medSøknadId(outgoing.soknadId)
            .medVedleggTitler()
            .medVedleggUrls(outgoing.vedleggUrls)
            .somOutgoing()

        JSONAssert.assertEquals(outgoingFromIncoming.jsonObject.toString(), outgoing.jsonObject.toString(), true)
    }

    private fun gyldigSoknad(
        fodselsnummerSoker: String,
        søknadId: String? = null
    ): String =
        """
        {
            "${JsonKeys.søknadId}": ${
                when (søknadId) {
                    null -> null
                    else -> "$søknadId"
                }
            },
            "søker": {
                "fødselsnummer": "$fodselsnummerSoker",
                "aktørId": "123456"
            },
            "vedlegg": [],
            "hvilke_som_helst_andre_atributter": {
                "enabled": true,
                "norsk": "Sære Åreknuter"
            }
        }
        """.trimIndent()

    private fun hentSoknadSendtTilProsessering(soknadId: String?): JSONObject {
        assertNotNull(soknadId)
        return kafkaTestConsumer.hentSoknad(soknadId, topic = Topics.SØKNAD_MOTTATT).data
    }
}
