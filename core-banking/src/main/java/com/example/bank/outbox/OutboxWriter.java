package com.example.bank.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an event row in the <b>caller's</b> DB transaction (MANDATORY propagation). The event
 * therefore exists if and only if the business change committed - no "DB committed but Kafka
 * send failed" and no "Kafka got an event for a rolled-back transfer".
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transactionCompleted(TransactionCompletedEvent event) {
        // Partition key = sending account, so all events of one account stay ordered in one partition.
        String key = String.valueOf(event.fromAccountId() != null ? event.fromAccountId() : event.toAccountId());
        append(event.eventId(), "Account", key, TransactionCompletedEvent.EVENT_TYPE, event);
    }

    private void append(UUID id, String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            outbox.save(new OutboxEvent(id, aggregateType, aggregateId, eventType,
                    objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + eventType, e);
        }
    }
}
