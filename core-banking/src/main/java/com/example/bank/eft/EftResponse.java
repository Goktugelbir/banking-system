package com.example.bank.eft;

import com.example.bank.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EftResponse(UUID sagaId, UUID transactionId, Long fromAccountId, String targetIban, BigDecimal amount,
                          Currency currency, EftSaga.Status status, int attempts, String lastError,
                          Instant createdAt, Instant updatedAt) {

    public static EftResponse of(EftSaga s) {
        return new EftResponse(s.getId(), s.getTransactionId(), s.getAccountId(), s.getTargetIban(), s.getAmount(),
                s.getCurrency(), s.getStatus(), s.getAttempts(), s.getLastError(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
