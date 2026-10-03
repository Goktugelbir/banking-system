package com.example.bank.outbox;

import com.example.bank.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to Kafka (via the outbox) whenever money has finally moved. */
public record TransactionCompletedEvent(
        UUID eventId,
        UUID transactionId,
        String type,
        Long fromAccountId,
        Long fromCustomerId,
        Long toAccountId,
        String externalIban,
        BigDecimal amount,
        Currency currency,
        Instant occurredAt) {

    public static final String EVENT_TYPE = "TransactionCompleted";

    /** Fraud rules use the receiver as "payee" identity, internal or external. */
    public String payeeKey() {
        return toAccountId != null ? "acc:" + toAccountId : "iban:" + externalIban;
    }
}
