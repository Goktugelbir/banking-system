package com.example.bank.ledger;

import com.example.bank.account.Account;
import com.example.bank.common.Currency;
import com.example.bank.ledger.LedgerEntry.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only place where balances change. Every call writes a balanced set of ledger entries
 * (sum of debits == sum of credits per currency) and updates the cached balance column of the
 * already-locked accounts in the same database transaction.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository entries;

    public LedgerService(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(UUID transactionId, List<Posting> postings, Map<Long, Account> lockedAccounts) {
        assertBalanced(postings, lockedAccounts);
        for (Posting p : postings) {
            Account account = lockedAccounts.get(p.accountId());
            if (p.direction() == Direction.DEBIT) {
                account.applyDebit(p.amount());
            } else {
                account.applyCredit(p.amount());
            }
            entries.save(new LedgerEntry(transactionId, account.getId(), p.direction(), p.amount(),
                    account.getCurrency(), account.getBalance()));
        }
    }

    private static void assertBalanced(List<Posting> postings, Map<Long, Account> accounts) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two postings");
        }
        Map<Currency, BigDecimal> net = new EnumMap<>(Currency.class);
        for (Posting p : postings) {
            Account account = accounts.get(p.accountId());
            if (account == null) {
                throw new IllegalStateException("Account " + p.accountId() + " was not locked before posting");
            }
            if (p.amount().signum() <= 0) {
                throw new IllegalArgumentException("Posting amounts must be positive");
            }
            BigDecimal signed = p.direction() == Direction.CREDIT ? p.amount() : p.amount().negate();
            net.merge(account.getCurrency(), signed, BigDecimal::add);
        }
        net.forEach((currency, sum) -> {
            if (sum.signum() != 0) {
                throw new IllegalStateException("Unbalanced postings in " + currency + ": " + sum);
            }
        });
    }
}
