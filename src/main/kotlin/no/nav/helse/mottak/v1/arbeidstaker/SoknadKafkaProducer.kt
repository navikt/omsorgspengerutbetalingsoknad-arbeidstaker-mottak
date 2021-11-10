package no.nav.helse.mottak.v1.arbeidstaker

import no.nav.helse.Metadata
import no.nav.helse.dusseldorf.ktor.health.HealthCheck
import no.nav.helse.dusseldorf.ktor.health.Healthy
import no.nav.helse.dusseldorf.ktor.health.Result
import no.nav.helse.dusseldorf.ktor.health.UnHealthy
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.TopicEntry
import no.nav.helse.kafka.TopicUse
import no.nav.helse.kafka.Topics
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer
import org.json.JSONObject
import org.slf4j.LoggerFactory

internal class SoknadKafkaProducer(
    val kafkaConfig: KafkaConfig
) : HealthCheck {
    private companion object {
        private val NAME = "SoknadV1Producer"

        private val TOPIC_USE = TopicUse(
            name = Topics.SØKNAD_MOTTATT,
            valueSerializer = SoknadOutgoingSerializer()
        )

        private val logger = LoggerFactory.getLogger(SoknadKafkaProducer::class.java)
    }

    private val producer = KafkaProducer(
        kafkaConfig.producer(NAME),
        TOPIC_USE.keySerializer(),
        TOPIC_USE.valueSerializer
    )


    internal fun produce(
        soknad: SoknadOutgoing,
        metadata: Metadata
    ) {
        if (metadata.version != 1) throw IllegalStateException("Kan ikke legge søknad om utbetaling for arbeidstaker på versjon ${metadata.version} til prosessering.")

        val recordMetaData = producer.send(
            ProducerRecord(
                TOPIC_USE.name,
                soknad.soknadId.id,
                TopicEntry(
                    metadata = metadata,
                    data = soknad.jsonObject
                )
            )
        ).get()

        logger.info("Søknad om utbetaling for arbeidstaker sendt til Topic '${TOPIC_USE.name}' med offset '${recordMetaData.offset()}' til partition '${recordMetaData.partition()}'")
    }

    internal fun stop() = producer.close()

    override suspend fun check(): Result {
        return try {
            producer.partitionsFor(TOPIC_USE.name)
            Healthy(NAME, "Tilkobling til Kafka OK!")
        } catch (cause: Throwable) {
            logger.error("Feil ved tilkobling til Kafka", cause)
            UnHealthy(NAME, "Feil ved tilkobling mot Kafka. ${cause.message}")
        }
    }
}

private class SoknadOutgoingSerializer : Serializer<TopicEntry<JSONObject>> {
    override fun serialize(topic: String, data: TopicEntry<JSONObject>): ByteArray {
        val metadata = JSONObject()
            .put("correlationId", data.metadata.correlationId)
            .put("version", data.metadata.version)

        return JSONObject()
            .put("metadata", metadata)
            .put("data", data.data)
            .toString()
            .toByteArray()
    }

    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
    override fun close() {}
}
