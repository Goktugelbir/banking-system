package com.example.externalbank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A deliberately unreliable "other bank".
 *
 * <ul>
 *   <li>{@code reject-rate} of requests are rejected (e.g. unknown IBAN).</li>
 *   <li>{@code timeout-rate} of requests answer after {@code slow-response} - longer than the caller's
 *       timeout. Half of those are executed <i>before</i> the delay (the transfer happened, only the
 *       answer is lost), the other half only <i>after</i> it (so a cancel arriving meanwhile wins).
 *       That is exactly the ambiguity a real inter-bank integration has to survive.</li>
 * </ul>
 *
 * The reference is the idempotency key: every state change is a compare-and-set on a
 * ConcurrentHashMap entry, so once a reference is CANCELLED it can never become ACCEPTED and vice versa.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    public enum Status { ACCEPTED, REJECTED, CANCELLED }

    public record TransferRequest(@NotBlank String reference, @NotBlank String iban,
                                  @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                  @NotBlank String currency) {
    }

    public record TransferResponse(String reference, Status status, String reason) {
    }

    public record Record(String reference, String iban, BigDecimal amount, String currency, Status status,
                         String reason, Instant at) {
    }

    private final Map<String, Record> ledger = new ConcurrentHashMap<>();
    private final double timeoutRate;
    private final double rejectRate;
    private final long slowResponseMs;

    public TransferController(@Value("${external-bank.timeout-rate}") double timeoutRate,
                              @Value("${external-bank.reject-rate}") double rejectRate,
                              @Value("${external-bank.slow-response-ms}") long slowResponseMs) {
        this.timeoutRate = timeoutRate;
        this.rejectRate = rejectRate;
        this.slowResponseMs = slowResponseMs;
    }

    @PostMapping
    public TransferResponse transfer(@Valid @RequestBody TransferRequest req) throws InterruptedException {
        Record existing = ledger.get(req.reference());
        if (existing != null) {
            return toResponse(existing); // idempotent replay
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < rejectRate) {
            return toResponse(decide(req, Status.REJECTED, "Beneficiary account not found"));
        }
        if (random.nextDouble() < timeoutRate) {
            boolean executeFirst = random.nextBoolean();
            log.info("Simulating slow response for {} (executed {} the delay)", req.reference(),
                    executeFirst ? "before" : "after");
            Record record = executeFirst ? decide(req, Status.ACCEPTED, null) : null;
            Thread.sleep(slowResponseMs);
            if (record == null) {
                record = decide(req, Status.ACCEPTED, null);
            }
            return toResponse(record);
        }
        return toResponse(decide(req, Status.ACCEPTED, null));
    }

    /** Cancel wins only if the transfer was not executed yet; otherwise reports the existing outcome. */
    @PostMapping("/{reference}/cancel")
    public TransferResponse cancel(@PathVariable String reference) {
        Record cancelled = new Record(reference, null, null, null, Status.CANCELLED, "Cancelled by sender",
                Instant.now());
        Record winner = ledger.merge(reference, cancelled, (current, ignored) -> current);
        log.info("Cancel {} -> {}", reference, winner.status());
        return toResponse(winner);
    }

    @GetMapping("/{reference}")
    public TransferResponse get(@PathVariable String reference) {
        Record r = ledger.get(reference);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return toResponse(r);
    }

    @GetMapping
    public Collection<Record> all() {
        return ledger.values();
    }

    /** First writer wins: if a cancel already landed, the late transfer is refused. */
    private Record decide(TransferRequest req, Status status, String reason) {
        Record proposed = new Record(req.reference(), req.iban(), req.amount(), req.currency(), status, reason,
                Instant.now());
        Record winner = ledger.merge(req.reference(), proposed, (current, ignored) -> current);
        log.info("Transfer {} {} {} -> {}", req.reference(), req.amount(), req.currency(), winner.status());
        return winner;
    }

    private static TransferResponse toResponse(Record r) {
        return new TransferResponse(r.reference(), r.status(), r.reason());
    }
}
