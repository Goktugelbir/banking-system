package com.example.bank.outbox;

import com.example.bank.config.BankProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The relay: polls unpublished outbox rows and pushes them to Kafka.
 *
 * <p>Delivery is <b>at-least-once</b>: if the app dies after Kafka acked but before the row is marked
 * published, the event is sent again. Consumers de-duplicate by {@code eventId}.
 * Rows are sent in creation order and the batch stops at the first failure so per-account
 * ordering is preserved.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final BankProperties.Outbox config;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka, BankProperties props) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.config = props.outbox();
    }

    @Scheduled(fixedDelayString = "${bank.outbox.poll-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.lockNextBatch(config.batchSize());
        for (OutboxEvent event : batch) {
            try {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(config.topic(), event.getAggregateId(), event.getPayload());
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception e) {
                log.warn("Outbox publish failed for {}: {}", event.getId(), e.toString());
                event.markFailed(e.toString());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
    }
}
