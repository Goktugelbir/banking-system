package com.example.bank.idempotency;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService.TransferCommand;
import com.example.bank.transaction.TransactionResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.bank.Concurrency.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIT extends AbstractIntegrationTest {

    @Test
    void sameKeySentConcurrentlyMovesMoneyOnce() throws Exception {
        AuthUser owner = newCustomer();
        Account from = openAccount(owner, Currency.TRY, "1000.00");
        Account to = openAccount(owner, Currency.TRY, "0");
        String key = UUID.randomUUID().toString();
        TransferCommand cmd = new TransferCommand(from.getId(), to.getId(), new BigDecimal("100.00"), "rent");

        Set<UUID> transactionIds = ConcurrentHashMap.newKeySet();
        runConcurrently(20, i -> transactionIds.add(money.transfer(owner, key, cmd).id()));

        assertThat(transactionIds).hasSize(1);
        assertThat(balance(from.getId())).isEqualByComparingTo("900.00");
        assertThat(balance(to.getId())).isEqualByComparingTo("100.00");
        assertLedgerConsistent();
    }

    @Test
    void retryReturnsOriginalResult() {
        AuthUser owner = newCustomer();
        Account from = openAccount(owner, Currency.TRY, "500.00");
        Account to = openAccount(owner, Currency.TRY, "0");
        String key = UUID.randomUUID().toString();
        TransferCommand cmd = new TransferCommand(from.getId(), to.getId(), new BigDecimal("10.00"), null);

        TransactionResponse first = money.transfer(owner, key, cmd);
        TransactionResponse retry = money.transfer(owner, key, cmd);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(balance(from.getId())).isEqualByComparingTo("490.00");
    }

    @Test
    void sameKeyDifferentPayloadIsRejected() {
        AuthUser owner = newCustomer();
        Account from = openAccount(owner, Currency.TRY, "500.00");
        Account to = openAccount(owner, Currency.TRY, "0");
        String key = UUID.randomUUID().toString();
        money.transfer(owner, key, new TransferCommand(from.getId(), to.getId(), new BigDecimal("10.00"), null));

        assertThatThrownBy(() -> money.transfer(owner, key,
                new TransferCommand(from.getId(), to.getId(), new BigDecimal("99.00"), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void keysAreScopedPerCustomer() {
        AuthUser alice = newCustomer();
        AuthUser bob = newCustomer();
        Account a1 = openAccount(alice, Currency.TRY, "100.00");
        Account a2 = openAccount(alice, Currency.TRY, "0");
        Account b1 = openAccount(bob, Currency.TRY, "100.00");
        Account b2 = openAccount(bob, Currency.TRY, "0");
        String sharedKey = "order-42";

        money.transfer(alice, sharedKey, new TransferCommand(a1.getId(), a2.getId(), new BigDecimal("5.00"), null));
        money.transfer(bob, sharedKey, new TransferCommand(b1.getId(), b2.getId(), new BigDecimal("5.00"), null));

        assertThat(balance(a2.getId())).isEqualByComparingTo("5.00");
        assertThat(balance(b2.getId())).isEqualByComparingTo("5.00");
    }
}
