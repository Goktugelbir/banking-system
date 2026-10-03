package com.example.bank.eft;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.account.InternalAccounts;
import com.example.bank.account.InternalAccounts.Purpose;
import com.example.bank.common.Currency;
import com.example.bank.eft.EftSagaSteps.EftCommand;
import com.example.bank.eft.FakeExternalBank.Mode;
import com.example.bank.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EftSagaIT extends AbstractIntegrationTest {

    private static final String IBAN = "TR330006100519786457841326";

    @Autowired
    EftSagaOrchestrator orchestrator;
    @Autowired
    EftSagaRepository sagas;
    @Autowired
    FakeExternalBank externalBank;
    @Autowired
    InternalAccounts internal;

    @AfterEach
    void resetBank() {
        externalBank.reset();
    }

    @Test
    void acceptedTransferCompletes() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        externalBank.mode(Mode.ACCEPT);

        EftResponse result = send(user, account, "250.00");

        assertThat(result.status()).isEqualTo(EftSaga.Status.COMPLETED);
        assertThat(balance(account.getId())).isEqualByComparingTo("750.00");
        assertLedgerConsistent();
    }

    @Test
    void rejectedTransferIsCompensated() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        externalBank.mode(Mode.REJECT);

        EftResponse result = send(user, account, "250.00");

        assertThat(result.status()).isEqualTo(EftSaga.Status.COMPENSATED);
        assertThat(balance(account.getId())).isEqualByComparingTo("1000.00");
        assertLedgerConsistent();
    }

    @Test
    void timeoutAfterExecutionIsConfirmedNotRefunded() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        externalBank.mode(Mode.TIMEOUT_EXECUTED);

        EftResponse result = send(user, account, "250.00");

        // Cancel answered "already accepted" -> the money really left, so we must NOT refund it.
        assertThat(result.status()).isEqualTo(EftSaga.Status.COMPLETED);
        assertThat(balance(account.getId())).isEqualByComparingTo("750.00");
        assertLedgerConsistent();
    }

    @Test
    void timeoutBeforeExecutionIsCancelledAndCompensated() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        externalBank.mode(Mode.TIMEOUT_NOT_EXECUTED);

        EftResponse result = send(user, account, "250.00");
        externalBank.deliverLateRequests(); // the slow request arrives after the cancel

        assertThat(result.status()).isEqualTo(EftSaga.Status.COMPENSATED);
        assertThat(externalBank.acceptedReferences()).doesNotContain(result.sagaId().toString());
        assertThat(balance(account.getId())).isEqualByComparingTo("1000.00");
        assertLedgerConsistent();
    }

    @Test
    void inDoubtSagaKeepsFundsHeldUntilRecovered() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        externalBank.mode(Mode.TIMEOUT_NOT_EXECUTED);
        externalBank.cancelAvailable(false);

        EftResponse result = send(user, account, "250.00");

        assertThat(result.status()).isEqualTo(EftSaga.Status.IN_DOUBT);
        assertThat(balance(account.getId())).as("money stays blocked").isEqualByComparingTo("750.00");

        externalBank.cancelAvailable(true);
        orchestrator.resolve(result.sagaId());

        assertThat(sagas.findById(result.sagaId()).orElseThrow().getStatus()).isEqualTo(EftSaga.Status.COMPENSATED);
        assertThat(balance(account.getId())).isEqualByComparingTo("1000.00");
        assertLedgerConsistent();
    }

    @Test
    void replayedRequestDoesNotSendTwice() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.TRY, "1000.00");
        String key = UUID.randomUUID().toString();
        EftCommand cmd = new EftCommand(account.getId(), IBAN, new BigDecimal("100.00"), null);

        EftResponse first = orchestrator.start(user, key, cmd);
        EftResponse second = orchestrator.start(user, key, cmd);

        assertThat(second.sagaId()).isEqualTo(first.sagaId());
        assertThat(balance(account.getId())).isEqualByComparingTo("900.00");
    }

    /**
     * 60 EFTs against a bank that randomly accepts, rejects and times out (before or after
     * executing). After recovery: every saga is terminal, both banks agree on exactly which
     * transfers happened, no money is stuck in suspense and the ledger balances.
     */
    @Test
    void chaosRunStaysConsistent() {
        AuthUser user = newCustomer();
        Account account = openAccount(user, Currency.USD, "100000.00");
        BigDecimal suspenseBefore = balance(internal.id(Purpose.EFT_SUSPENSE, Currency.USD));
        externalBank.mode(Mode.CHAOS);

        List<UUID> sagaIds = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            sagaIds.add(send(user, account, "10.00").sagaId());
        }
        externalBank.deliverLateRequests();
        sagaIds.forEach(orchestrator::resolve);

        List<EftSaga> finished = sagas.findAllById(sagaIds);
        assertThat(finished).allMatch(s -> s.getStatus().isTerminal());

        Set<String> completedHere = finished.stream()
                .filter(s -> s.getStatus() == EftSaga.Status.COMPLETED)
                .map(s -> s.getId().toString())
                .collect(Collectors.toSet());
        Set<String> acceptedThere = externalBank.acceptedReferences().stream()
                .filter(ref -> sagaIds.contains(UUID.fromString(ref)))
                .collect(Collectors.toSet());
        assertThat(completedHere).isEqualTo(acceptedThere);

        BigDecimal sent = new BigDecimal("10.00").multiply(BigDecimal.valueOf(completedHere.size()));
        assertThat(balance(account.getId())).isEqualByComparingTo(new BigDecimal("100000.00").subtract(sent));
        assertThat(balance(internal.id(Purpose.EFT_SUSPENSE, Currency.USD))).isEqualByComparingTo(suspenseBefore);
        assertLedgerConsistent();
    }

    private EftResponse send(AuthUser user, Account account, String amount) {
        return orchestrator.start(user, UUID.randomUUID().toString(),
                new EftCommand(account.getId(), IBAN, new BigDecimal(amount), "test"));
    }
}
