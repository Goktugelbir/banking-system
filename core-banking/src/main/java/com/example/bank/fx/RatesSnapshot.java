package com.example.bank.fx;

import com.example.bank.common.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** All rates expressed as "units of currency per 1 USD"; any cross rate is derived from these. */
public record RatesSnapshot(Map<Currency, BigDecimal> unitsPerUsd, Instant fetchedAt, String source) {
}
