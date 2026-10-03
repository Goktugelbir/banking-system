package com.example.bank.account;

import com.example.bank.common.Currency;
import com.example.bank.ledger.LedgerEntry;
import com.example.bank.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts")
public class AccountController {

    public record OpenAccountRequest(@NotNull Currency currency) {
    }

    public record AccountResponse(Long id, String accountNumber, Currency currency, BigDecimal balance,
                                  Account.Status status, Long customerId) {
        public static AccountResponse of(Account a) {
            return new AccountResponse(a.getId(), a.getAccountNumber(), a.getCurrency(), a.getBalance(),
                    a.getStatus(), a.getCustomerId());
        }
    }

    public record StatementLine(Long entryId, UUID transactionId, LedgerEntry.Direction direction, BigDecimal amount,
                                BigDecimal balanceAfter, Instant at) {
        static StatementLine of(LedgerEntry e) {
            return new StatementLine(e.getId(), e.getTransactionId(), e.getDirection(), e.getAmount(),
                    e.getBalanceAfter(), e.getCreatedAt());
        }
    }

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a new account in the given currency")
    public AccountResponse open(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.of(accounts.open(user, req.currency()));
    }

    @GetMapping
    @Operation(summary = "List my accounts")
    public List<AccountResponse> mine(@AuthenticationPrincipal AuthUser user) {
        return accounts.mine(user).stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of my accounts")
    public AccountResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
        return AccountResponse.of(accounts.get(user, id));
    }

    @GetMapping("/{id}/statement")
    @Operation(summary = "Ledger entries of an account, newest first")
    public List<StatementLine> statement(@AuthenticationPrincipal AuthUser user, @PathVariable Long id,
                                         @RequestParam(defaultValue = "50") int limit) {
        return accounts.statement(user, id, limit).stream().map(StatementLine::of).toList();
    }
}
