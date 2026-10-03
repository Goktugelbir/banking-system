package com.example.bank.admin;

import com.example.bank.account.Account;
import com.example.bank.account.AccountController.AccountResponse;
import com.example.bank.account.AccountService;
import com.example.bank.fraud.FraudAlert;
import com.example.bank.fraud.FraudService;
import com.example.bank.ledger.ReconciliationService;
import com.example.bank.outbox.OutboxRepository;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService;
import com.example.bank.transaction.MoneyMovementService.CashCommand;
import com.example.bank.transaction.TransactionController;
import com.example.bank.transaction.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin")
public class AdminController {

    public record DepositRequest(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                 String description) {
    }

    public record StatusRequest(@NotNull Account.Status status) {
    }

    public record DecisionRequest(@NotNull FraudAlert.Status decision) {
    }

    private final MoneyMovementService money;
    private final AccountService accounts;
    private final ReconciliationService reconciliation;
    private final FraudService fraud;
    private final OutboxRepository outbox;

    public AdminController(MoneyMovementService money, AccountService accounts, ReconciliationService reconciliation,
                           FraudService fraud, OutboxRepository outbox) {
        this.money = money;
        this.accounts = accounts;
        this.reconciliation = reconciliation;
        this.fraud = fraud;
        this.outbox = outbox;
    }

    @PostMapping("/accounts/{accountId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cash deposit into a customer account (teller operation)")
    public TransactionResponse deposit(@AuthenticationPrincipal AuthUser admin, @PathVariable Long accountId,
                                       @RequestHeader(TransactionController.IDEMPOTENCY_HEADER) String idempotencyKey,
                                       @Valid @RequestBody DepositRequest req) {
        return money.deposit(admin, idempotencyKey,
                new CashCommand(accountId, req.amount(), req.description() == null ? "Cash deposit" : req.description()));
    }

    @PutMapping("/accounts/{accountId}/status")
    @Operation(summary = "Freeze / unfreeze an account")
    public AccountResponse setStatus(@PathVariable Long accountId, @Valid @RequestBody StatusRequest req) {
        return AccountResponse.of(accounts.changeStatus(accountId, req.status()));
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Check cached balances against the ledger and the double-entry invariant")
    public ReconciliationService.Report reconcile() {
        return reconciliation.reconcile();
    }

    @GetMapping("/fraud/alerts")
    @Operation(summary = "Open fraud alerts")
    public List<FraudAlert> openAlerts() {
        return fraud.openAlerts();
    }

    @PostMapping("/fraud/alerts/{alertId}/decision")
    @Operation(summary = "RELEASED (false positive) or CONFIRMED (fraud)")
    public FraudAlert decide(@PathVariable Long alertId, @Valid @RequestBody DecisionRequest req) {
        return fraud.resolve(alertId, req.decision());
    }

    @GetMapping("/outbox")
    @Operation(summary = "Outbox backlog")
    public Map<String, Long> outbox() {
        return Map.of("unpublished", outbox.countByPublishedAtIsNull());
    }
}
