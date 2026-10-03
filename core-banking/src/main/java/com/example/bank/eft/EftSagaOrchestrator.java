package com.example.bank.eft;

import com.example.bank.account.AccountService;
import com.example.bank.common.BusinessException;
import com.example.bank.config.BankProperties;
import com.example.bank.eft.EftSagaSteps.EftCommand;
import com.example.bank.eft.ExternalBankClient.ExternalStatus;
import com.example.bank.eft.ExternalBankClient.TransferRequest;
import com.example.bank.eft.ExternalBankClient.TransferResponse;
import com.example.bank.idempotency.IdempotencyService;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.TransactionalExecutor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestration-based saga for outgoing EFT. The orchestrator owns the workflow; the steps are
 * local transactions. The remote call is deliberately <b>outside</b> any DB transaction so no
 * row lock is held while waiting on the network.
 *
 * <p>The tricky case is a timeout: we do not know whether the other bank executed the transfer.
 * Blindly compensating could refund money that has actually left (the bank loses it); blindly
 * completing could take money for a transfer that never happened. Instead we ask the external bank
 * to cancel the reference - its answer tells us the truth, and after a successful cancel a late
 * delivery of the original request is refused, so the two sides cannot diverge.
 */
@Service
public class EftSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EftSagaOrchestrator.class);

    private final IdempotencyService idempotency;
    private final TransactionalExecutor executor;
    private final EftSagaSteps steps;
    private final EftSagaRepository sagas;
    private final ExternalBankClient externalBank;
    private final AccountService accounts;
    private final BankProperties.Eft config;

    public EftSagaOrchestrator(IdempotencyService idempotency, TransactionalExecutor executor, EftSagaSteps steps,
                               EftSagaRepository sagas, ExternalBankClient externalBank, AccountService accounts,
                               BankProperties props) {
        this.idempotency = idempotency;
        this.executor = executor;
        this.steps = steps;
        this.sagas = sagas;
        this.externalBank = externalBank;
        this.accounts = accounts;
        this.config = props.eft();
    }

    public EftResponse start(AuthUser user, String idempotencyKey, EftCommand cmd) {
        IdempotencyService.Result<UUID> result = idempotency.execute(user.id(), idempotencyKey, cmd,
                ctx -> executor.execute(() -> steps.reserve(ctx, user, cmd)),
                id -> id);
        UUID sagaId = result.value();
        if (!result.replayed()) {
            send(sagaId);
        }
        return get(user, sagaId);
    }

    public EftResponse get(AuthUser user, UUID sagaId) {
        EftSaga saga = sagas.findById(sagaId).orElseThrow(() -> BusinessException.notFound("EFT " + sagaId));
        accounts.get(user, saga.getAccountId()); // 404 unless the caller owns the source account
        return EftResponse.of(saga);
    }

    void send(UUID sagaId) {
        EftSaga saga = executor.execute(() -> steps.recordAttempt(sagaId));
        try {
            TransferResponse response = externalBank.send(new TransferRequest(sagaId.toString(),
                    saga.getTargetIban(), saga.getAmount(), saga.getCurrency()));
            apply(sagaId, response);
        } catch (CallNotPermittedException e) {
            // Circuit open: the request was never sent, so it is safe to compensate right away.
            executor.execute(() -> steps.compensate(sagaId, "External bank unavailable (circuit open)"));
        } catch (Exception e) {
            log.warn("EFT {} outcome unknown: {}", sagaId, e.toString());
            executor.execute(() -> steps.markInDoubt(sagaId, e.toString()));
            resolve(sagaId);
        }
    }

    /** Cancel-or-confirm: turn an unknown outcome into a known one using the external bank's answer. */
    void resolve(UUID sagaId) {
        try {
            executor.execute(() -> steps.recordAttempt(sagaId));
            apply(sagaId, externalBank.cancel(sagaId.toString()));
        } catch (Exception e) {
            log.warn("EFT {} still in doubt, will retry: {}", sagaId, e.toString());
        }
    }

    private void apply(UUID sagaId, TransferResponse response) {
        if (response == null || response.status() == null) {
            throw new IllegalStateException("Empty response from external bank");
        }
        if (response.status() == ExternalStatus.ACCEPTED) {
            executor.execute(() -> steps.complete(sagaId));
        } else {
            String reason = response.status() + (response.reason() != null ? ": " + response.reason() : "");
            executor.execute(() -> steps.compensate(sagaId, reason));
        }
    }

    /**
     * Crash/timeout recovery. Picks up sagas stuck in a non-terminal state (process died after
     * reserving funds, or the cancel call failed) and resolves them with cancel-or-confirm.
     */
    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT10S")
    public void recoverStuckSagas() {
        Instant before = Instant.now().minus(config.recoveryAfter());
        List<UUID> stuck = sagas.findStuck(List.of(EftSaga.Status.FUNDS_RESERVED, EftSaga.Status.IN_DOUBT), before);
        for (UUID id : stuck) {
            log.info("Recovering EFT saga {}", id);
            resolve(id);
        }
    }
}
