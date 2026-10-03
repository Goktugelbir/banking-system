package com.example.bank.eft;

import com.example.bank.account.Account;
import com.example.bank.account.AccountLockManager;
import com.example.bank.account.AccountRepository;
import com.example.bank.account.AccountService;
import com.example.bank.account.InternalAccounts;
import com.example.bank.account.InternalAccounts.Purpose;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.idempotency.IdempotencyService.Context;
import com.example.bank.idempotency.IdempotencyService;
import com.example.bank.ledger.LedgerService;
import com.example.bank.ledger.Posting;
import com.example.bank.outbox.OutboxWriter;
import com.example.bank.outbox.TransactionCompletedEvent;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.BankTransaction;
import com.example.bank.transaction.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The local, ACID steps of the EFT saga. Each one is its own DB transaction (callers wrap them in
 * {@code TransactionalExecutor}) and each is idempotent: completing or compensating a saga that
 * already reached a terminal state is a no-op, so the synchronous flow and the recovery job can
 * never apply an outcome twice.
 *
 * <p>Money flow (all through the double-entry ledger):
 * <pre>
 *   reserve:     customer        -> EFT_SUSPENSE          ("bloke")
 *   complete:    EFT_SUSPENSE    -> EXTERNAL_SETTLEMENT   (money left the bank)
 *   compensate:  EFT_SUSPENSE    -> customer              (hold released)
 * </pre>
 */
@Component
public class EftSagaSteps {

    public record EftCommand(Long fromAccountId, String targetIban, BigDecimal amount, String description) {
    }

    private final IdempotencyService idempotency;
    private final AccountLockManager locks;
    private final AccountRepository accountRepository;
    private final InternalAccounts internal;
    private final LedgerService ledger;
    private final TransactionRepository transactions;
    private final EftSagaRepository sagas;
    private final OutboxWriter outbox;

    public EftSagaSteps(IdempotencyService idempotency, AccountLockManager locks, AccountRepository accountRepository,
                        InternalAccounts internal, LedgerService ledger, TransactionRepository transactions,
                        EftSagaRepository sagas, OutboxWriter outbox) {
        this.idempotency = idempotency;
        this.locks = locks;
        this.accountRepository = accountRepository;
        this.internal = internal;
        this.ledger = ledger;
        this.transactions = transactions;
        this.sagas = sagas;
        this.outbox = outbox;
    }

    /** Step 1: put a hold on the customer's money by moving it to the suspense account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID reserve(Context ctx, AuthUser user, EftCommand cmd) {
        UUID sagaId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        idempotency.claim(ctx, sagaId);

        Currency currency = locks.currencyOf(cmd.fromAccountId());
        Long suspense = internal.id(Purpose.EFT_SUSPENSE, currency);
        Map<Long, Account> accounts = locks.load(List.of(cmd.fromAccountId(), suspense));
        Account from = accounts.get(cmd.fromAccountId());
        AccountService.requireAccess(from, user);
        if (from.getType() != Account.Type.CUSTOMER) {
            throw BusinessException.notFound("Account " + from.getId());
        }
        from.requireCanSend();
        BigDecimal amount = currency.normalize(cmd.amount());

        BankTransaction tx = new BankTransaction(txId, BankTransaction.Type.EFT_OUT, from.getId(), null, amount,
                currency, cmd.description(), user.id()).withExternalIban(cmd.targetIban());
        transactions.saveAndFlush(tx);
        ledger.post(txId, List.of(Posting.debit(from.getId(), amount), Posting.credit(suspense, amount)), accounts);
        sagas.save(new EftSaga(sagaId, txId, from.getId(), amount, currency, cmd.targetIban()));
        return sagaId;
    }

    /** Step 2a: external bank confirmed - release the hold towards the settlement (nostro) account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EftSaga complete(UUID sagaId) {
        EftSaga saga = lockSaga(sagaId);
        if (saga.getStatus().isTerminal()) {
            return saga;
        }
        Long suspense = internal.id(Purpose.EFT_SUSPENSE, saga.getCurrency());
        Long settlement = internal.id(Purpose.EXTERNAL_SETTLEMENT, saga.getCurrency());
        Map<Long, Account> accounts = locks.load(List.of(suspense, settlement));
        ledger.post(saga.getTransactionId(),
                List.of(Posting.debit(suspense, saga.getAmount()), Posting.credit(settlement, saga.getAmount())),
                accounts);

        BankTransaction tx = transactions.findById(saga.getTransactionId()).orElseThrow();
        tx.complete();
        saga.transition(EftSaga.Status.COMPLETED, null);

        Long customerId = accountRepository.findById(saga.getAccountId()).map(Account::getCustomerId).orElse(null);
        outbox.transactionCompleted(new TransactionCompletedEvent(UUID.randomUUID(), tx.getId(), tx.getType().name(),
                saga.getAccountId(), customerId, null, saga.getTargetIban(), saga.getAmount(), saga.getCurrency(),
                Instant.now()));
        return saga;
    }

    /** Step 2b (compensation): the transfer will never happen - give the money back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EftSaga compensate(UUID sagaId, String reason) {
        EftSaga saga = lockSaga(sagaId);
        if (saga.getStatus().isTerminal()) {
            return saga;
        }
        Long suspense = internal.id(Purpose.EFT_SUSPENSE, saga.getCurrency());
        Map<Long, Account> accounts = locks.load(List.of(saga.getAccountId(), suspense));
        ledger.post(saga.getTransactionId(),
                List.of(Posting.debit(suspense, saga.getAmount()), Posting.credit(saga.getAccountId(), saga.getAmount())),
                accounts);

        transactions.findById(saga.getTransactionId()).orElseThrow().fail();
        saga.transition(EftSaga.Status.COMPENSATED, reason);
        return saga;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EftSaga markInDoubt(UUID sagaId, String error) {
        EftSaga saga = lockSaga(sagaId);
        if (!saga.getStatus().isTerminal()) {
            saga.transition(EftSaga.Status.IN_DOUBT, error);
        }
        return saga;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EftSaga recordAttempt(UUID sagaId) {
        EftSaga saga = lockSaga(sagaId);
        saga.recordAttempt();
        return saga;
    }

    private EftSaga lockSaga(UUID sagaId) {
        return sagas.findByIdForUpdate(sagaId).orElseThrow(() -> BusinessException.notFound("EFT " + sagaId));
    }
}
