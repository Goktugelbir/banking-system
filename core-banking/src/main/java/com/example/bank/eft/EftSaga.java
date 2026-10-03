package com.example.bank.eft;

import com.example.bank.common.AssignedIdEntity;
import com.example.bank.common.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent state of one outgoing EFT. Because every transition is stored, a crash at any point
 * can be recovered: the recovery job picks up non-terminal sagas and drives them to an end state.
 *
 * <pre>
 *   FUNDS_RESERVED --accepted--------------------> COMPLETED
 *        |        --rejected / circuit open-----> COMPENSATED
 *        +--timeout--> IN_DOUBT --cancel says "accepted"--> COMPLETED
 *                               --cancel says "cancelled"--> COMPENSATED
 *                               --cancel fails--> stays IN_DOUBT (recovery job retries)
 * </pre>
 */
@Entity
@Table(name = "eft_sagas")
public class EftSaga extends AssignedIdEntity {

    public enum Status {
        FUNDS_RESERVED, IN_DOUBT, COMPLETED, COMPENSATED;

        public boolean isTerminal() {
            return this == COMPLETED || this == COMPENSATED;
        }
    }

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "target_iban", nullable = false)
    private String targetIban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EftSaga() {
    }

    public EftSaga(UUID id, UUID transactionId, Long accountId, BigDecimal amount, Currency currency,
                   String targetIban) {
        super(id);
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.targetIban = targetIban;
        this.status = Status.FUNDS_RESERVED;
    }

    void transition(Status next, String error) {
        this.status = next;
        this.updatedAt = Instant.now();
        if (error != null) {
            this.lastError = error.substring(0, Math.min(error.length(), 1000));
        }
    }

    void recordAttempt() {
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getTargetIban() {
        return targetIban;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
