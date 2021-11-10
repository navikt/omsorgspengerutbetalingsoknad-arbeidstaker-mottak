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
import no.nav.helse.SøknadId
import no.nav.helse.getSøknadId

internal fun Route.SøknadApi(
    soknadV1MottakService: SoknadMottakService
) {
    post("v1/soknad") {
        val metadata: Metadata = call.metadata()
        val søknad: SoknadIncoming = withContext(Dispatchers.IO) { call.søknad() }
        val søknadId: SøknadId = søknad.søknadId ?: call.getSøknadId()

        soknadV1MottakService.leggTilProsessering(
            søknadId = søknadId,
            metadata = metadata,
            soknad = søknad
        )

        call.respond(HttpStatusCode.Accepted, mapOf("id" to søknadId.id))
    }
}


private suspend fun ApplicationCall.søknad(): SoknadIncoming {
    val json = receiveStream().use { String(it.readAllBytes(), Charsets.UTF_8) }
    val incoming = SoknadIncoming(json)
    incoming.validate()
    return incoming
}

fun ApplicationCall.metadata() = Metadata(
    version = 1,
    correlationId = request.getCorrelationId()
)

fun ApplicationRequest.getCorrelationId(): String {
    return header(HttpHeaders.XCorrelationId) ?: throw IllegalStateException("Correlation Id ikke satt")
}
