package com.example.bank.eft;

import com.example.bank.eft.EftSagaSteps.EftCommand;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.TransactionController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers/eft")
@Tag(name = "EFT (inter-bank, saga)")
public class EftController {

    public record EftRequest(@NotNull Long fromAccountId,
                             @NotNull @Pattern(regexp = "[A-Z]{2}[0-9A-Z]{13,32}", message = "must be an IBAN")
                             String targetIban,
                             @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                             @Size(max = 255) String description) {
    }

    private final EftSagaOrchestrator orchestrator;

    public EftController(EftSagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send money to another bank. Final status: COMPLETED, COMPENSATED or IN_DOUBT (resolved later)")
    public EftResponse send(@AuthenticationPrincipal AuthUser user,
                            @RequestHeader(TransactionController.IDEMPOTENCY_HEADER) String idempotencyKey,
                            @Valid @RequestBody EftRequest req) {
        return orchestrator.start(user, idempotencyKey,
                new EftCommand(req.fromAccountId(), req.targetIban(), req.amount(), req.description()));
    }

    @GetMapping("/{sagaId}")
    @Operation(summary = "Current state of an EFT saga")
    public EftResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID sagaId) {
        return orchestrator.get(user, sagaId);
    }
}
