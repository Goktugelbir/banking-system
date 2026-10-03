package com.example.bank.transaction;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService.CashCommand;
import com.example.bank.transaction.MoneyMovementService.TransferCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.bank.Concurrency.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/** PESSIMISTIC (default) locking under heavy contention. */
class ConcurrentTransferIT extends AbstractIntegrationTest {

    @Test
    void hundredConcurrentWithdrawalsNeverOverdraw() throws Exception {
        AuthUser owner = newCustomer();
        Account account = openAccount(owner, Currency.TRY, "1000.00");

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        runConcurrently(100, i -> {
            try {
                money.withdraw(owner, UUID.randomUUID().toString(),
                        new CashCommand(account.getId(), new BigDecimal("50.00"), "atm"));
                succeeded.incrementAndGet();
            } catch (BusinessException e) {
                assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                insufficient.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(20);
        assertThat(insufficient.get()).isEqualTo(80);
        assertThat(balance(account.getId())).isEqualByComparingTo("0");
        assertLedgerConsistent();
    }

    /** A->B and B->A at the same time: without ordered locking this deadlocks. */
    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        AuthUser owner = newCustomer();
        Account a = openAccount(owner, Currency.TRY, "1000.00");
        Account b = openAccount(owner, Currency.TRY, "1000.00");

        runConcurrently(100, i -> {
            boolean aToB = i % 2 == 0;
            money.transfer(owner, UUID.randomUUID().toString(), new TransferCommand(
                    aToB ? a.getId() : b.getId(), aToB ? b.getId() : a.getId(), new BigDecimal("7.00"), null));
        });

        assertThat(balance(a.getId())).isEqualByComparingTo("1000.00");
        assertThat(balance(b.getId())).isEqualByComparingTo("1000.00");
        assertLedgerConsistent();
    }
}
