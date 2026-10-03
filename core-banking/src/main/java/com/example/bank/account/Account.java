package com.example.bank.account;

import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    public enum Type { CUSTOMER, INTERNAL }

    public enum Status {
        ACTIVE,
        /** Fraud review pending - may receive money but cannot send. */
        UNDER_REVIEW,
        /** Frozen by fraud/admin - may receive money but cannot send. */
        FROZEN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "internal_code")
    private String internalCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    /** Used by OPTIMISTIC locking mode; harmlessly incremented under PESSIMISTIC mode too. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Account() {
    }

    public Account(Long customerId, String accountNumber, Currency currency) {
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.type = Type.CUSTOMER;
    }

    public void applyDebit(BigDecimal amount) {
        BigDecimal next = balance.subtract(amount);
        if (type == Type.CUSTOMER && next.signum() < 0) {
            throw BusinessException.insufficientFunds();
        }
        balance = next;
    }

    public void applyCredit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public void requireCanSend() {
        if (status != Status.ACTIVE) {
            throw BusinessException.conflict("ACCOUNT_" + status.name(),
                    "Account " + accountNumber + " is " + status.name().toLowerCase() + " and cannot send money");
        }
    }

    public void changeStatus(Status status) {
        this.status = status;
    }

    public boolean isOwnedBy(Long customerId) {
        return this.customerId != null && this.customerId.equals(customerId);
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Type getType() {
        return type;
    }

    public String getInternalCode() {
        return internalCode;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Status getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
