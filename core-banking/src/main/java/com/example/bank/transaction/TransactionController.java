package com.example.bank.transaction;

import com.example.bank.account.AccountService;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService.CashCommand;
import com.example.bank.transaction.MoneyMovementService.TransferCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Transactions")
public class TransactionController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    public record TransferRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId,
                                  @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                  @Size(max = 255) String description) {
    }

    public record WithdrawRequest(@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
    }

    private final MoneyMovementService money;
    private final AccountService accounts;
    private final TransactionRepository transactions;

    public TransactionController(MoneyMovementService money, AccountService accounts,
                                 TransactionRepository transactions) {
        this.money = money;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @PostMapping("/api/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Transfer between two accounts of this bank (same currency)")
    public TransactionResponse transfer(@AuthenticationPrincipal AuthUser user,
                                        @Parameter(description = "Unique per logical request; retries reuse it")
                                        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                        @Valid @RequestBody TransferRequest req) {
        return money.transfer(user, idempotencyKey,
                new TransferCommand(req.fromAccountId(), req.toAccountId(), req.amount(), req.description()));
    }

    @PostMapping("/api/accounts/{accountId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Withdraw cash from an account")
    public TransactionResponse withdraw(@AuthenticationPrincipal AuthUser user, @PathVariable Long accountId,
                                        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                        @Valid @RequestBody WithdrawRequest req) {
        return money.withdraw(user, idempotencyKey, new CashCommand(accountId, req.amount(), "Cash withdrawal"));
    }

    @GetMapping("/api/transactions/{id}")
    @Operation(summary = "Get a transaction I initiated")
    public TransactionResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return money.get(user, id);
    }

    @GetMapping("/api/accounts/{accountId}/transactions")
    @Operation(summary = "Transactions touching one of my accounts, newest first")
    public List<TransactionResponse> history(@AuthenticationPrincipal AuthUser user, @PathVariable Long accountId,
                                             @RequestParam(defaultValue = "50") int limit) {
        accounts.get(user, accountId);
        return transactions.findByAccount(accountId, PageRequest.of(0, Math.min(limit, 500))).stream()
                .map(TransactionResponse::of).toList();
    }
}
