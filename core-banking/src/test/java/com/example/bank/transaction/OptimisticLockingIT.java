package com.example.bank.transaction;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService.TransferCommand;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.bank.Concurrency.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/** Same guarantees with @Version + retry instead of row locks. */
@TestPropertySource(properties = {
        "bank.locking.mode=OPTIMISTIC",
        "bank.locking.optimistic-max-attempts=500"
})
class OptimisticLockingIT extends AbstractIntegrationTest {

    @Test
    void concurrentTransfersWithOptimisticLockingNeverOverdraw() throws Exception {
        AuthUser owner = newCustomer();
        Account source = openAccount(owner, Currency.EUR, "1000.00");
        Account target = openAccount(owner, Currency.EUR, "0");

        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(40, i -> {
            try {
                money.transfer(owner, UUID.randomUUID().toString(),
                        new TransferCommand(source.getId(), target.getId(), new BigDecimal("50.00"), null));
                succeeded.incrementAndGet();
            } catch (BusinessException e) {
                assertThat(e.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
            }
        });

        assertThat(succeeded.get()).isEqualTo(20);
        assertThat(balance(source.getId())).isEqualByComparingTo("0");
        assertThat(balance(target.getId())).isEqualByComparingTo("1000.00");
        assertLedgerConsistent();
    }
}
