package com.example.bank.fx;

import com.example.bank.common.Currency;
import com.example.bank.config.BankProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Fiat rates from open.er-api.com (free, no API key), crypto prices from CoinGecko.
 * Guarded by the {@code fxProvider} circuit breaker: after repeated failures calls are rejected
 * immediately (no thread pile-up waiting for timeouts) and FxRateService falls back to the
 * last known good snapshot.
 */
@Component
@ConditionalOnProperty(name = "bank.fx.provider", havingValue = "live", matchIfMissing = true)
public class LiveRateSource implements RateSource {

    private static final Map<Currency, String> COINGECKO_IDS = Map.of(Currency.BTC, "bitcoin", Currency.ETH, "ethereum");

    private final RestClient http;
    private final BankProperties.Fx config;

    public LiveRateSource(BankProperties props) {
        this.config = props.fx();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    @CircuitBreaker(name = "fxProvider")
    public RatesSnapshot fetch() {
        Map<Currency, BigDecimal> perUsd = new EnumMap<>(Currency.class);

        JsonNode fiat = http.get().uri(config.fiatUrl()).retrieve().body(JsonNode.class);
        if (fiat == null || !"success".equals(fiat.path("result").asText())) {
            throw new IllegalStateException("Fiat rate provider returned an error");
        }
        for (Currency c : Currency.values()) {
            if (!c.isCrypto()) {
                JsonNode rate = fiat.path("rates").path(c.name());
                if (rate.isMissingNode()) {
                    throw new IllegalStateException("No fiat rate for " + c);
                }
                perUsd.put(c, rate.decimalValue());
            }
        }

        JsonNode crypto = http.get().uri(config.cryptoUrl()).retrieve().body(JsonNode.class);
        for (var e : COINGECKO_IDS.entrySet()) {
            JsonNode usdPrice = crypto == null ? null : crypto.path(e.getValue()).path("usd");
            if (usdPrice == null || usdPrice.isMissingNode() || usdPrice.decimalValue().signum() <= 0) {
                throw new IllegalStateException("No crypto price for " + e.getKey());
            }
            perUsd.put(e.getKey(), BigDecimal.ONE.divide(usdPrice.decimalValue(), MathContext.DECIMAL128));
        }
        return new RatesSnapshot(perUsd, Instant.now(), "open.er-api.com+coingecko");
    }
}
