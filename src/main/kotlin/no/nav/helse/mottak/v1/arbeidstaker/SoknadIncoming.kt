package no.nav.helse.mottak.v1.arbeidstaker

import no.nav.helse.SøknadId
import no.nav.helse.AktoerId
import no.nav.helse.mottak.v1.JsonKeys
import org.json.JSONObject
import java.net.URI
import java.util.*

internal class SoknadIncoming(json: String) {
    private val jsonObject = JSONObject(json)
    internal val vedlegg: List<Vedlegg>
    internal val søknadId: SøknadId?

    internal val sokerFodselsNummer = jsonObject.getJSONObject(JsonKeys.søker).getString(
        JsonKeys.fødselsnummer
    )

    private fun hentSøknadId(): SøknadId? = when (val søknadId = jsonObject.optString(JsonKeys.søknadId, "")) {
        "" -> null
        else -> SøknadId(søknadId)
    }

    private fun hentVedlegg(): List<Vedlegg> {
        val vedlegg = mutableListOf<Vedlegg>()
        jsonObject.getJSONArray(JsonKeys.vedlegg).forEach {
            val vedleggJson = it as JSONObject
            vedlegg.add(
                Vedlegg(
                    content = Base64.getDecoder().decode(vedleggJson.getString(JsonKeys.content)),
                    contentType = vedleggJson.getString(JsonKeys.contentType),
                    title = vedleggJson.getString(JsonKeys.title)
                )
            )
        }

        return vedlegg.toList()
    }

    init {
        vedlegg = hentVedlegg()
        jsonObject.remove(JsonKeys.vedlegg)
        søknadId = hentSøknadId()
    }

    internal val søkerAktørId = AktoerId(
        jsonObject.getJSONObject(JsonKeys.søker).getString(
            JsonKeys.aktørId
        )
    )

    internal fun medSøknadId(søknadId: SøknadId): SoknadIncoming {
        jsonObject.put(JsonKeys.søknadId, søknadId.id)
        return this
    }

    internal fun medVedleggTitler(): SoknadIncoming {
        val listeOverTitler = mutableListOf<String>()
        for (vedlegg in vedlegg) {
            listeOverTitler.add(vedlegg.title)
        }
        jsonObject.put(JsonKeys.titler, listeOverTitler)
        return this
    }

    internal fun medVedleggUrls(vedleggUrls: List<URI>): SoknadIncoming {
        jsonObject.put(JsonKeys.vedleggUrls, vedleggUrls)
        return this
    }

    internal fun somOutgoing() =
        SoknadOutgoing(
            jsonObject
        )

}

internal class SoknadOutgoing(internal val jsonObject: JSONObject) {
    internal val soknadId = SøknadId(jsonObject.getString(JsonKeys.søknadId))
    internal val vedleggUrls = hentVedleggUrls()

    private fun hentVedleggUrls(): List<URI> {
        val vedleggUrls = mutableListOf<URI>()
        jsonObject.getJSONArray(JsonKeys.vedleggUrls).forEach {
            vedleggUrls.add(URI(it as String))
        }
        return vedleggUrls.toList()
    }

}

data class Vedlegg(
    val content: ByteArray,
    val contentType: String,
    val title: String
)
