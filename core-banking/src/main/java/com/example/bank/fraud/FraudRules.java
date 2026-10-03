package com.example.bank.fraud;

import com.example.bank.config.BankProperties;
import com.example.bank.fraud.FraudAlert.Action;
import com.example.bank.outbox.TransactionCompletedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateful fraud rules backed by Redis. Events of one account arrive in order on one Kafka
 * partition (partition key = sending account), so per-account state is updated sequentially.
 */
@Component
public class FraudRules {

    public record Hit(String rule, Action action, String details) {
    }

    private final StringRedisTemplate redis;
    private final BankProperties.Fraud config;
    private final ZoneId zone;

    public FraudRules(StringRedisTemplate redis, BankProperties props) {
        this.redis = redis;
        this.config = props.fraud();
        this.zone = ZoneId.of(config.zone());
    }

    public List<Hit> evaluate(TransactionCompletedEvent e) {
        List<Hit> hits = new ArrayList<>();
        velocity(e, hits);
        nightHighAmount(e, hits);
        newPayeeHighAmount(e, hits);
        return hits;
    }

    /**
     * Sliding window with a sorted set: score = event time, member = transaction id.
     * Drop everything older than the window, then count what is left. Using the transaction id as
     * member makes a redelivered event a no-op (ZADD of an existing member just updates the score).
     */
    private void velocity(TransactionCompletedEvent e, List<Hit> hits) {
        String key = "fraud:velocity:" + e.fromAccountId();
        long now = e.occurredAt().toEpochMilli();
        long windowStart = now - config.velocityWindow().toMillis();
        redis.opsForZSet().add(key, e.transactionId().toString(), now);
        redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStart);
        Long count = redis.opsForZSet().zCard(key);
        redis.expire(key, config.velocityWindow().plus(Duration.ofMinutes(1)));
        if (count != null && count > config.velocityMaxTransfers()) {
            hits.add(new Hit("VELOCITY", Action.FREEZE_ACCOUNT,
                    count + " outgoing transfers within " + config.velocityWindow().toMinutes() + " minutes"));
        }
    }

    private void nightHighAmount(TransactionCompletedEvent e, List<Hit> hits) {
        int hour = e.occurredAt().atZone(zone).getHour();
        BigDecimal threshold = config.nightHighAmount().get(e.currency());
        if (hour >= config.nightStartHour() && hour < config.nightEndHour()
                && threshold != null && e.amount().compareTo(threshold) >= 0) {
            hits.add(new Hit("NIGHT_HIGH_AMOUNT", Action.MANUAL_REVIEW,
                    e.amount() + " " + e.currency() + " at " + hour + ":00 local time"));
        }
    }

    /** SADD returns 1 only the first time a payee is seen for this account. */
    private void newPayeeHighAmount(TransactionCompletedEvent e, List<Hit> hits) {
        Long added = redis.opsForSet().add("fraud:payees:" + e.fromAccountId(), e.payeeKey());
        BigDecimal threshold = config.newPayeeHighAmount().get(e.currency());
        if (added != null && added == 1 && threshold != null && e.amount().compareTo(threshold) >= 0) {
            hits.add(new Hit("NEW_PAYEE_HIGH_AMOUNT", Action.MANUAL_REVIEW,
                    "First transfer to " + e.payeeKey() + ": " + e.amount() + " " + e.currency()));
        }
    }
}
