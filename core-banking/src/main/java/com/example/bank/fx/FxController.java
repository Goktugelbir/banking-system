package com.example.bank.fx;

import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.TransactionController;
import com.example.bank.transaction.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/fx")
@Tag(name = "FX / Crypto")
public class FxController {

    public record QuoteRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId,
                               @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal sellAmount) {
    }

    private final FxRateService rates;
    private final FxQuoteService quotes;

    public FxController(FxRateService rates, FxQuoteService quotes) {
        this.rates = rates;
        this.quotes = quotes;
    }

    @GetMapping("/rates")
    @Operation(summary = "Indicative mid-market rate (1 from = rate to)")
    public FxRateService.Rate rate(@RequestParam Currency from, @RequestParam Currency to) {
        return rates.rate(from, to);
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Get a firm quote valid for 30 seconds")
    public FxQuote quote(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody QuoteRequest req) {
        return quotes.createQuote(user, req.fromAccountId(), req.toAccountId(), req.sellAmount());
    }

    @PostMapping("/quotes/{quoteId}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Execute the exchange at the quoted rate")
    public TransactionResponse accept(@AuthenticationPrincipal AuthUser user, @PathVariable UUID quoteId,
                                      @RequestHeader(TransactionController.IDEMPOTENCY_HEADER) String idempotencyKey) {
        return quotes.accept(user, quoteId, idempotencyKey);
    }
}
