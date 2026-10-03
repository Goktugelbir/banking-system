package com.example.bank.transaction;

import com.example.bank.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        BankTransaction.Type type,
        BankTransaction.Status status,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        Currency currency,
        BigDecimal counterAmount,
        Currency counterCurrency,
        BigDecimal fxRate,
        String externalIban,
        String description,
        Instant createdAt,
        Instant completedAt) {

    public static TransactionResponse of(BankTransaction t) {
        return new TransactionResponse(t.getId(), t.getType(), t.getStatus(), t.getFromAccountId(),
                t.getToAccountId(), t.getAmount(), t.getCurrency(), t.getCounterAmount(), t.getCounterCurrency(),
                t.getFxRate(), t.getExternalIban(), t.getDescription(), t.getCreatedAt(), t.getCompletedAt());
    }
}
