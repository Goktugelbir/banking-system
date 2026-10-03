package com.example.bank.ledger;

import com.example.bank.ledger.LedgerEntry.Direction;

import java.math.BigDecimal;

/** One leg of a double-entry transaction. */
public record Posting(Long accountId, Direction direction, BigDecimal amount) {

    public static Posting debit(Long accountId, BigDecimal amount) {
        return new Posting(accountId, Direction.DEBIT, amount);
    }

    public static Posting credit(Long accountId, BigDecimal amount) {
        return new Posting(accountId, Direction.CREDIT, amount);
    }
}
