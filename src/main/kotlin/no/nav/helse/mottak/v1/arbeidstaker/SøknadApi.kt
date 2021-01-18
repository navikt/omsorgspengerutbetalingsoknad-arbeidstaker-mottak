package no.nav.helse.mottak.v1.arbeidstaker

import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.ApplicationRequest
import io.ktor.request.header
import io.ktor.request.receiveStream
import io.ktor.response.ApplicationResponse
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.helse.Metadata
import no.nav.helse.SoknadId
import no.nav.helse.getSoknadId

internal fun Route.SøknadApi(
    soknadV1MottakService: SoknadMottakService,
    dittNavV1Service: DittNavV1Service
) {
    post("v1/soknad") {
        val soknadId: SoknadId = call.getSoknadId()
        val metadata: Metadata = call.metadata()
        val soknad: SoknadIncoming = withContext(Dispatchers.IO) {call.søknad()}
        soknadV1MottakService.leggTilProsessering(
            soknadId = soknadId,
            metadata = metadata,
            soknad = soknad
        )
        sendBeskjedTilDittNav(
            dittNavV1Service = dittNavV1Service,
            dittNavTekst = "Søknad om utbetaling av omsorgspenger er mottatt.",
            dittNavLink = "",
            sokerFodselsNummer = soknad.sokerFodselsNummer,
            soknadId = soknadId
        )
        call.respond(HttpStatusCode.Accepted, mapOf("id" to soknadId.id))
    }
}


private suspend fun ApplicationCall.søknad() : SoknadIncoming {
    val json = receiveStream().use { String(it.readAllBytes(), Charsets.UTF_8) }
    val incoming =
        SoknadIncoming(json)
    incoming.validate()
    return incoming
}

fun ApplicationCall.metadata() = Metadata(
    version = 1,
    correlationId = request.getCorrelationId(),
    requestId = response.getRequestId()
)

fun ApplicationRequest.getCorrelationId(): String {
    return header(HttpHeaders.XCorrelationId) ?: throw IllegalStateException("Correlation Id ikke satt")
}

fun ApplicationResponse.getRequestId(): String {
    return headers[HttpHeaders.XRequestId] ?: throw IllegalStateException("Request Id ikke satt")
}
