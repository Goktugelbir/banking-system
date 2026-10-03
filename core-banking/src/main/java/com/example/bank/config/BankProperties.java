package com.example.bank.config;

import com.example.bank.common.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(
        Security security,
        Admin admin,
        Locking locking,
        Outbox outbox,
        Fx fx,
        Eft eft,
        Fraud fraud) {

    public record Security(String jwtSecret, Duration jwtTtl) {
    }

    public record Admin(String email, String password) {
    }

    public enum LockingMode { PESSIMISTIC, OPTIMISTIC }

    public record Locking(LockingMode mode, int optimisticMaxAttempts) {
    }

    public record Outbox(String topic, long pollIntervalMs, int batchSize) {
    }

    public record Fx(String provider, String fiatUrl, String cryptoUrl, Duration cacheTtl,
                     Duration maxStaleness, Duration quoteTtl, BigDecimal spread) {
    }

    public record Eft(String externalBankUrl, Duration timeout, Duration recoveryAfter) {
    }

    public record Fraud(int velocityMaxTransfers, Duration velocityWindow, int nightStartHour, int nightEndHour,
                        String zone, Map<Currency, BigDecimal> nightHighAmount,
                        Map<Currency, BigDecimal> newPayeeHighAmount) {
    }
}
