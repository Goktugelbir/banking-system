package com.example.bank.idempotency;

import com.example.bank.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Exactly-once semantics for money-moving requests.
 *
 * <p>How it works: the operation inserts (customer_id, idempotency_key) into {@code idempotency_records}
 * <b>inside the same DB transaction</b> that moves the money. Consequences:
 * <ul>
 *   <li>Sequential retry: the record is found up-front and the original result is returned.</li>
 *   <li>Concurrent duplicates: the second INSERT blocks on the primary-key index until the first
 *       transaction finishes. If the first committed, the second gets a unique violation, rolls back
 *       and replays the stored result. If the first rolled back, the second simply proceeds.</li>
 *   <li>Money and key can never diverge: they commit or roll back together.</li>
 *   <li>Same key with a different payload is rejected (422) - that is a client bug, not a retry.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public record Context(Long customerId, String key, String requestHash) {
    }

    public record Result<T>(T value, boolean replayed) {
    }

    private record StoredRecord(String requestHash, UUID resourceId) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * @param operation performs the work in its own transaction and must call {@link #claim} inside it
     * @param loader    turns the resulting resource id into the response (used for replays too)
     */
    public <T> Result<T> execute(Long customerId, String key, Object request,
                                 Function<Context, UUID> operation, Function<UUID, T> loader) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw BusinessException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required (max 100 chars)");
        }
        Context ctx = new Context(customerId, key, hash(request));

        Optional<StoredRecord> existing = find(customerId, key);
        if (existing.isPresent()) {
            return new Result<>(replay(existing.get(), ctx, loader), true);
        }
        try {
            UUID resourceId = operation.apply(ctx);
            return new Result<>(loader.apply(resourceId), false);
        } catch (DuplicateKeyException e) {
            // Lost the race against a concurrent request with the same key, which has now committed.
            StoredRecord winner = find(customerId, key).orElseThrow(() -> e);
            return new Result<>(replay(winner, ctx, loader), true);
        }
    }

    /** Must run inside the money-moving transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(Context ctx, UUID resourceId) {
        jdbc.update("""
                INSERT INTO idempotency_records (customer_id, idempotency_key, request_hash, resource_id)
                VALUES (?, ?, ?, ?)
                """, ctx.customerId(), ctx.key(), ctx.requestHash(), resourceId);
    }

    private <T> T replay(StoredRecord stored, Context ctx, Function<UUID, T> loader) {
        if (!stored.requestHash().equals(ctx.requestHash())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request body");
        }
        return loader.apply(stored.resourceId());
    }

    private Optional<StoredRecord> find(Long customerId, String key) {
        return jdbc.query("""
                        SELECT request_hash, resource_id FROM idempotency_records
                        WHERE customer_id = ? AND idempotency_key = ?
                        """,
                (rs, i) -> new StoredRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                customerId, key).stream().findFirst();
    }

    private String hash(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
