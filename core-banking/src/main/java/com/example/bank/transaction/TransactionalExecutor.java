package com.example.bank.transaction;

import com.example.bank.account.AccountLockManager;
import com.example.bank.config.BankProperties;
import com.example.bank.config.BankProperties.LockingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a unit of work in its own DB transaction. Under OPTIMISTIC locking a version conflict
 * rolls the whole transaction back and it is retried from scratch (re-reading fresh balances),
 * with jittered back-off so the competing threads do not collide again in lock-step.
 */
@Component
public class TransactionalExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransactionalExecutor.class);

    private final TransactionTemplate tx;
    private final LockingMode mode;
    private final int maxAttempts;

    public TransactionalExecutor(PlatformTransactionManager txManager, AccountLockManager lockManager,
                                 BankProperties props) {
        this.tx = new TransactionTemplate(txManager);
        this.mode = lockManager.mode();
        this.maxAttempts = props.locking().optimisticMaxAttempts();
    }

    public <T> T execute(Supplier<T> work) {
        if (mode == LockingMode.PESSIMISTIC) {
            return tx.execute(status -> work.get());
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.warn("Giving up after {} optimistic lock conflicts", attempt);
                    throw e;
                }
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        long max = Math.min(200, 5L * attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, max + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
        }
    }
}
