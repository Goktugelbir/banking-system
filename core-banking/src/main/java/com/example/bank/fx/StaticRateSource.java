package com.example.bank.fx;

import com.example.bank.common.Currency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Deterministic rates for offline development and tests ({@code bank.fx.provider=static}). */
@Component
@ConditionalOnProperty(name = "bank.fx.provider", havingValue = "static")
public class StaticRateSource implements RateSource {

    @Override
    public RatesSnapshot fetch() {
        Map<Currency, BigDecimal> perUsd = new EnumMap<>(Currency.class);
        perUsd.put(Currency.USD, BigDecimal.ONE);
        perUsd.put(Currency.TRY, new BigDecimal("40.00"));
        perUsd.put(Currency.EUR, new BigDecimal("0.80"));
        perUsd.put(Currency.GBP, new BigDecimal("0.75"));
        perUsd.put(Currency.BTC, BigDecimal.ONE.divide(new BigDecimal("100000"), MathContext.DECIMAL128));
        perUsd.put(Currency.ETH, BigDecimal.ONE.divide(new BigDecimal("4000"), MathContext.DECIMAL128));
        return new RatesSnapshot(perUsd, Instant.now(), "static");
    }
}
