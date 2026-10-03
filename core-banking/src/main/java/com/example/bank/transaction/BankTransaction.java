package com.example.bank.transaction;

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

/** Business-level record of a money movement; the ledger entries are its accounting legs. */
@Entity
@Table(name = "transactions")
public class BankTransaction extends AssignedIdEntity {

    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER, FX_EXCHANGE, EFT_OUT }

    public enum Status { PENDING, COMPLETED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "from_account_id")
    private Long fromAccountId;

    @Column(name = "to_account_id")
    private Long toAccountId;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(name = "counter_amount", precision = 38, scale = 8)
    private BigDecimal counterAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "counter_currency")
    private Currency counterCurrency;

    @Column(name = "fx_rate", precision = 38, scale = 12)
    private BigDecimal fxRate;

    @Column(name = "quote_id", unique = true)
    private UUID quoteId;

    @Column(name = "external_iban")
    private String externalIban;

    private String description;

    @Column(name = "initiated_by")
    private Long initiatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected BankTransaction() {
    }

    public BankTransaction(UUID id, Type type, Long fromAccountId, Long toAccountId, BigDecimal amount,
                           Currency currency, String description, Long initiatedBy) {
        super(id);
        this.type = type;
        this.status = Status.PENDING;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.initiatedBy = initiatedBy;
    }

    public BankTransaction withFx(BigDecimal counterAmount, Currency counterCurrency, BigDecimal rate, UUID quoteId) {
        this.counterAmount = counterAmount;
        this.counterCurrency = counterCurrency;
        this.fxRate = rate;
        this.quoteId = quoteId;
        return this;
    }

    public BankTransaction withExternalIban(String iban) {
        this.externalIban = iban;
        return this;
    }

    public void complete() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }

    public Type getType() {
        return type;
    }

    public Status getStatus() {
        return status;
    }

    public Long getFromAccountId() {
        return fromAccountId;
    }

    public Long getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getCounterAmount() {
        return counterAmount;
    }

    public Currency getCounterCurrency() {
        return counterCurrency;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public String getExternalIban() {
        return externalIban;
    }

    public String getDescription() {
        return description;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
