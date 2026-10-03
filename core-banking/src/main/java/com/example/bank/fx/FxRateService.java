package com.example.bank.fx;

import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.config.BankProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Rate lookup with two Redis keys:
 * <ul>
 *   <li>{@code fx:rates} - short TTL cache (default 30s) so we do not call the provider per request</li>
 *   <li>{@code fx:rates:last-good} - no TTL, survives provider outages; used as a fallback while it is
 *       younger than {@code max-staleness}. Older than that we refuse to quote (503) rather than
 *       trade on a stale price.</li>
 * </ul>
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    static final String CACHE_KEY = "fx:rates";
    static final String LAST_GOOD_KEY = "fx:rates:last-good";

    public record Rate(Currency from, Currency to, BigDecimal rate, Instant asOf, String source, boolean stale) {
    }

    private final RateSource source;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BankProperties.Fx config;

    public FxRateService(RateSource source, StringRedisTemplate redis, ObjectMapper objectMapper,
                         BankProperties props) {
        this.source = source;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.config = props.fx();
    }

    /** Mid-market rate: 1 {@code from} = rate {@code to}. */
    public Rate rate(Currency from, Currency to) {
        SnapshotView view = snapshot();
        BigDecimal perUsdFrom = view.snapshot.unitsPerUsd().get(from);
        BigDecimal perUsdTo = view.snapshot.unitsPerUsd().get(to);
        BigDecimal rate = perUsdTo.divide(perUsdFrom, MathContext.DECIMAL128).setScale(12, RoundingMode.HALF_EVEN);
        return new Rate(from, to, rate, view.snapshot.fetchedAt(), view.snapshot.source(), view.stale);
    }

    private record SnapshotView(RatesSnapshot snapshot, boolean stale) {
    }

    private SnapshotView snapshot() {
        RatesSnapshot cached = read(CACHE_KEY);
        if (cached != null) {
            return new SnapshotView(cached, false);
        }
        try {
            RatesSnapshot fresh = source.fetch();
            String json = objectMapper.writeValueAsString(fresh);
            redis.opsForValue().set(CACHE_KEY, json, config.cacheTtl());
            redis.opsForValue().set(LAST_GOOD_KEY, json);
            return new SnapshotView(fresh, false);
        } catch (Exception e) {
            // Includes CallNotPermittedException when the circuit breaker is open.
            RatesSnapshot lastGood = read(LAST_GOOD_KEY);
            if (lastGood != null && Duration.between(lastGood.fetchedAt(), Instant.now())
                    .compareTo(config.maxStaleness()) < 0) {
                log.warn("Rate provider unavailable ({}), serving last good rates from {}", e.toString(),
                        lastGood.fetchedAt());
                return new SnapshotView(lastGood, true);
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FX_RATES_UNAVAILABLE",
                    "Exchange rates are temporarily unavailable");
        }
    }

    private RatesSnapshot read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RatesSnapshot.class);
        } catch (JsonProcessingException e) {
            log.warn("Corrupt rate cache entry {}", key);
            return null;
        }
    }
}
