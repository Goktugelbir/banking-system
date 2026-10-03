package com.example.bank.fraud;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService.TransferCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** End-to-end: transfer -> outbox row -> relay -> Kafka -> fraud consumer -> Redis rules -> account status. */
@TestPropertySource(properties = "bank.fraud.enabled=true")
@DirtiesContext // stop this context's Kafka listener afterwards so it cannot freeze accounts of other tests
class FraudDetectionIT extends AbstractIntegrationTest {

    @Autowired
    FraudAlertRepository alerts;

    @Test
    void moreThanThreeTransfersInFiveMinutesFreezesTheAccount() {
        AuthUser user = newCustomer();
        Account from = openAccount(user, Currency.TRY, "1000.00");
        Account to = openAccount(newCustomer(), Currency.TRY, "0");

        for (int i = 0; i < 4; i++) {
            money.transfer(user, UUID.randomUUID().toString(),
                    new TransferCommand(from.getId(), to.getId(), new BigDecimal("10.00"), null));
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(accountRepository.findById(from.getId()).orElseThrow().getStatus())
                        .isEqualTo(Account.Status.FROZEN));
        assertThat(alerts.findByAccountIdOrderByCreatedAtDesc(from.getId()))
                .anyMatch(a -> a.getRule().equals("VELOCITY"));

        assertThatThrownBy(() -> money.transfer(user, UUID.randomUUID().toString(),
                new TransferCommand(from.getId(), to.getId(), new BigDecimal("10.00"), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ACCOUNT_FROZEN");
    }

    @Test
    void largeFirstTransferToNewPayeeGoesToReview() {
        AuthUser user = newCustomer();
        Account from = openAccount(user, Currency.TRY, "20000.00");
        Account to = openAccount(newCustomer(), Currency.TRY, "0");

        money.transfer(user, UUID.randomUUID().toString(),
                new TransferCommand(from.getId(), to.getId(), new BigDecimal("6000.00"), null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(accountRepository.findById(from.getId()).orElseThrow().getStatus())
                        .isEqualTo(Account.Status.UNDER_REVIEW));
        assertThat(alerts.findByAccountIdOrderByCreatedAtDesc(from.getId()))
                .anyMatch(a -> a.getRule().equals("NEW_PAYEE_HIGH_AMOUNT"));
    }

    @Test
    void smallTransfersToKnownPayeeAreNotFlagged() throws InterruptedException {
        AuthUser user = newCustomer();
        Account from = openAccount(user, Currency.TRY, "1000.00");
        Account to = openAccount(newCustomer(), Currency.TRY, "0");

        money.transfer(user, UUID.randomUUID().toString(),
                new TransferCommand(from.getId(), to.getId(), new BigDecimal("10.00"), null));
        money.transfer(user, UUID.randomUUID().toString(),
                new TransferCommand(from.getId(), to.getId(), new BigDecimal("20.00"), null));

        Thread.sleep(3000);
        assertThat(accountRepository.findById(from.getId()).orElseThrow().getStatus())
                .isEqualTo(Account.Status.ACTIVE);
    }
}
