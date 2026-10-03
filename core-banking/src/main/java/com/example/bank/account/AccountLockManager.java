package com.example.bank.account;

import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.config.BankProperties;
import com.example.bank.config.BankProperties.LockingMode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Loads every account touched by a money movement.
 *
 * <p>PESSIMISTIC: rows are locked with SELECT ... FOR UPDATE <b>always in ascending id order</b>.
 * Two concurrent transfers A->B and B->A therefore both try to lock min(A,B) first, so one simply
 * waits for the other instead of each holding one lock and waiting for the other (deadlock).
 *
 * <p>OPTIMISTIC: rows are read without locks; the {@code @Version} column makes the UPDATE fail
 * if someone else changed the row in between, and the caller retries the whole transaction.
 */
@Component
public class AccountLockManager {

    private final AccountRepository accounts;
    private final LockingMode mode;

    public AccountLockManager(AccountRepository accounts, BankProperties props) {
        this.accounts = accounts;
        this.mode = props.locking().mode();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Account> load(Collection<Long> accountIds) {
        Map<Long, Account> result = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(accountIds)) {
            Account account = (mode == LockingMode.PESSIMISTIC ? accounts.findByIdForUpdate(id) : accounts.findById(id))
                    .orElseThrow(() -> BusinessException.notFound("Account " + id));
            result.put(id, account);
        }
        return result;
    }

    public Currency currencyOf(Long accountId) {
        return accounts.findCurrency(accountId).orElseThrow(() -> BusinessException.notFound("Account " + accountId));
    }

    public LockingMode mode() {
        return mode;
    }
}
