package no.nav.helse.mottak.v1.arbeidstaker

import no.nav.helse.AktoerId
import no.nav.helse.CorrelationId
import no.nav.helse.Metadata
import no.nav.helse.SøknadId
import no.nav.helse.dokument.Dokument
import no.nav.helse.dokument.DokumentGateway
import org.slf4j.LoggerFactory
import java.net.URI

internal class SoknadMottakService(
    private val dokumentGateway: DokumentGateway,
    private val soknadV1KafkaProducer: SoknadKafkaProducer
) {

    private companion object {
        private val logger = LoggerFactory.getLogger(SoknadMottakService::class.java)
    }

    internal suspend fun leggTilProsessering(
        søknadId: SøknadId,
        metadata: Metadata,
        soknad: SoknadIncoming
    ): SøknadId {
        val correlationId = CorrelationId(metadata.correlationId)

        logger.trace("Lagrer vedlegg")
        val vedleggUrls = lagreVedlegg(
            aktoerId = soknad.søkerAktørId,
            vedlegg = soknad.vedlegg,
            correlationId = correlationId
        )

        val outgoing: SoknadOutgoing = soknad
            .medVedleggTitler()
            .medVedleggUrls(vedleggUrls)
            .medSøknadId(søknadId)
            .somOutgoing()

        logger.info("Legger på kø")
        soknadV1KafkaProducer.produce(
            metadata = metadata,
            soknad = outgoing
        )

        return søknadId
    }

    private suspend fun lagreVedlegg(
        aktoerId: AktoerId,
        correlationId: CorrelationId,
        vedlegg: List<Vedlegg>
    ) : List<URI> {
        logger.info("Lagrer ${vedlegg.size} vedlegg.")
        return dokumentGateway.lagreDokmenter(
            dokumenter = vedlegg.somDokumenter(),
            correlationId = correlationId,
            aktoerId = aktoerId
        )
    }
}

private fun List<Vedlegg>.somDokumenter() = map {
    Dokument(
        content = it.content,
        contentType = it.contentType,
        title = it.title
    )
}.toSet()
