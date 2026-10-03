package com.example.bank.transaction;

import com.example.bank.account.Account;
import com.example.bank.account.AccountLockManager;
import com.example.bank.account.AccountService;
import com.example.bank.account.InternalAccounts;
import com.example.bank.account.InternalAccounts.Purpose;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.fx.FxQuote;
import com.example.bank.idempotency.IdempotencyService;
import com.example.bank.idempotency.IdempotencyService.Context;
import com.example.bank.ledger.LedgerService;
import com.example.bank.ledger.Posting;
import com.example.bank.outbox.OutboxWriter;
import com.example.bank.outbox.TransactionCompletedEvent;
import com.example.bank.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Same-bank money movements. Each public method:
 * <ol>
 *   <li>goes through the idempotency guard,</li>
 *   <li>runs in one DB transaction (retried on optimistic conflicts),</li>
 *   <li>locks all affected accounts in ascending id order,</li>
 *   <li>writes balanced ledger postings, the transaction row and the outbox event atomically.</li>
 * </ol>
 */
@Service
public class MoneyMovementService {

    public record TransferCommand(Long fromAccountId, Long toAccountId, BigDecimal amount, String description) {
    }

    public record CashCommand(Long accountId, BigDecimal amount, String description) {
    }

    private final IdempotencyService idempotency;
    private final TransactionalExecutor executor;
    private final AccountLockManager locks;
    private final InternalAccounts internal;
    private final LedgerService ledger;
    private final TransactionRepository transactions;
    private final OutboxWriter outbox;

    public MoneyMovementService(IdempotencyService idempotency, TransactionalExecutor executor,
                                AccountLockManager locks, InternalAccounts internal, LedgerService ledger,
                                TransactionRepository transactions, OutboxWriter outbox) {
        this.idempotency = idempotency;
        this.executor = executor;
        this.locks = locks;
        this.internal = internal;
        this.ledger = ledger;
        this.transactions = transactions;
        this.outbox = outbox;
    }

    public TransactionResponse transfer(AuthUser user, String idempotencyKey, TransferCommand cmd) {
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw BusinessException.badRequest("SAME_ACCOUNT", "Source and target account must differ");
        }
        return run(user, idempotencyKey, cmd, ctx -> {
            UUID txId = UUID.randomUUID();
            idempotency.claim(ctx, txId);
            Map<Long, Account> accounts = locks.load(List.of(cmd.fromAccountId(), cmd.toAccountId()));
            Account from = accounts.get(cmd.fromAccountId());
            Account to = accounts.get(cmd.toAccountId());

            AccountService.requireAccess(from, user);
            if (to.getType() != Account.Type.CUSTOMER) {
                throw BusinessException.notFound("Account " + to.getId());
            }
            from.requireCanSend();
            if (from.getCurrency() != to.getCurrency()) {
                throw BusinessException.badRequest("CURRENCY_MISMATCH",
                        "Accounts have different currencies; use an FX quote instead");
            }
            BigDecimal amount = from.getCurrency().normalize(cmd.amount());

            BankTransaction tx = newCompleted(txId, BankTransaction.Type.TRANSFER, from.getId(), to.getId(), amount,
                    from.getCurrency(), cmd.description(), user.id());
            ledger.post(txId, List.of(Posting.debit(from.getId(), amount), Posting.credit(to.getId(), amount)), accounts);
            publish(tx, from.getCustomerId());
            return txId;
        });
    }

    /** Cash withdrawal at the counter/ATM: customer account -> bank cash account. */
    public TransactionResponse withdraw(AuthUser user, String idempotencyKey, CashCommand cmd) {
        return run(user, idempotencyKey, cmd, ctx -> {
            UUID txId = UUID.randomUUID();
            idempotency.claim(ctx, txId);
            Long cashId = internal.id(Purpose.CASH, locks.currencyOf(cmd.accountId()));
            Map<Long, Account> accounts = locks.load(List.of(cmd.accountId(), cashId));
            Account account = accounts.get(cmd.accountId());

            AccountService.requireAccess(account, user);
            requireCustomerAccount(account);
            account.requireCanSend();
            BigDecimal amount = account.getCurrency().normalize(cmd.amount());

            BankTransaction tx = newCompleted(txId, BankTransaction.Type.WITHDRAWAL, account.getId(), null, amount,
                    account.getCurrency(), cmd.description(), user.id());
            ledger.post(txId, List.of(Posting.debit(account.getId(), amount), Posting.credit(cashId, amount)), accounts);
            publish(tx, account.getCustomerId());
            return txId;
        });
    }

    /** Cash deposit (admin/teller only): bank cash account -> customer account. */
    public TransactionResponse deposit(AuthUser admin, String idempotencyKey, CashCommand cmd) {
        return run(admin, idempotencyKey, cmd, ctx -> {
            UUID txId = UUID.randomUUID();
            idempotency.claim(ctx, txId);
            Long cashId = internal.id(Purpose.CASH, locks.currencyOf(cmd.accountId()));
            Map<Long, Account> accounts = locks.load(List.of(cmd.accountId(), cashId));
            Account account = accounts.get(cmd.accountId());
            requireCustomerAccount(account);
            BigDecimal amount = account.getCurrency().normalize(cmd.amount());

            BankTransaction tx = newCompleted(txId, BankTransaction.Type.DEPOSIT, null, account.getId(), amount,
                    account.getCurrency(), cmd.description(), admin.id());
            ledger.post(txId, List.of(Posting.debit(cashId, amount), Posting.credit(account.getId(), amount)), accounts);
            publish(tx, null);
            return txId;
        });
    }

    /**
     * Executes an accepted FX quote. Four legs, balanced per currency:
     * customer(from) -> bank FX position(from), bank FX position(to) -> customer(to).
     * {@code transactions.quote_id} is UNIQUE, so a quote can be consumed at most once even if
     * two requests with different idempotency keys race for it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID executeFx(Context ctx, AuthUser user, FxQuote quote) {
        UUID txId = UUID.randomUUID();
        idempotency.claim(ctx, txId);
        Long posFrom = internal.id(Purpose.FX_POSITION, quote.fromCurrency());
        Long posTo = internal.id(Purpose.FX_POSITION, quote.toCurrency());
        Map<Long, Account> accounts = locks.load(List.of(quote.fromAccountId(), quote.toAccountId(), posFrom, posTo));
        Account from = accounts.get(quote.fromAccountId());
        Account to = accounts.get(quote.toAccountId());

        AccountService.requireAccess(from, user);
        AccountService.requireAccess(to, user);
        from.requireCanSend();
        if (from.getCurrency() != quote.fromCurrency() || to.getCurrency() != quote.toCurrency()) {
            throw BusinessException.conflict("QUOTE_MISMATCH", "Quote does not match the accounts' currencies");
        }

        BankTransaction tx = newCompleted(txId, BankTransaction.Type.FX_EXCHANGE, from.getId(), to.getId(),
                quote.sellAmount(), quote.fromCurrency(), "FX " + quote.fromCurrency() + "->" + quote.toCurrency(),
                user.id());
        tx.withFx(quote.buyAmount(), quote.toCurrency(), quote.rate(), quote.id());
        ledger.post(txId, List.of(
                Posting.debit(from.getId(), quote.sellAmount()),
                Posting.credit(posFrom, quote.sellAmount()),
                Posting.debit(posTo, quote.buyAmount()),
                Posting.credit(to.getId(), quote.buyAmount())), accounts);
        publish(tx, from.getCustomerId());
        return txId;
    }

    @Transactional(readOnly = true)
    public TransactionResponse load(UUID id) {
        return transactions.findById(id).map(TransactionResponse::of)
                .orElseThrow(() -> BusinessException.notFound("Transaction " + id));
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(AuthUser user, UUID id) {
        BankTransaction tx = transactions.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Transaction " + id));
        if (!user.isAdmin() && !user.id().equals(tx.getInitiatedBy())) {
            throw BusinessException.notFound("Transaction " + id);
        }
        return TransactionResponse.of(tx);
    }

    private TransactionResponse run(AuthUser user, String key, Object request, Function<Context, UUID> work) {
        return idempotency.execute(user.id(), key, request,
                ctx -> executor.execute(() -> work.apply(ctx)),
                this::load).value();
    }

    private BankTransaction newCompleted(UUID id, BankTransaction.Type type, Long from, Long to, BigDecimal amount,
                                         Currency currency, String description, Long initiatedBy) {
        BankTransaction tx = new BankTransaction(id, type, from, to, amount, currency, description, initiatedBy);
        tx.complete();
        // Flush now: ledger rows (IDENTITY ids) are inserted immediately and reference this row.
        return transactions.saveAndFlush(tx);
    }

    private void publish(BankTransaction tx, Long fromCustomerId) {
        outbox.transactionCompleted(new TransactionCompletedEvent(UUID.randomUUID(), tx.getId(), tx.getType().name(),
                tx.getFromAccountId(), fromCustomerId, tx.getToAccountId(), tx.getExternalIban(), tx.getAmount(),
                tx.getCurrency(), Instant.now()));
    }

    private static void requireCustomerAccount(Account account) {
        if (account.getType() != Account.Type.CUSTOMER) {
            throw BusinessException.notFound("Account " + account.getId());
        }
    }
}
