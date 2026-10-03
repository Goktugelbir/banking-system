package com.example.bank.fx;

import com.example.bank.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A firm price offered to one customer for a short time. Stored in Redis with a TTL. */
public record FxQuote(
        UUID id,
        Long customerId,
        Long fromAccountId,
        Long toAccountId,
        Currency fromCurrency,
        Currency toCurrency,
        BigDecimal sellAmount,
        BigDecimal buyAmount,
        BigDecimal rate,
        BigDecimal midRate,
        Instant expiresAt) {
}
