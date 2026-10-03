package com.example.bank.account;

import com.example.bank.common.Currency;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves (and caches) the ids of the bank's own GL accounts seeded by Flyway. */
@Component
public class InternalAccounts {

    public enum Purpose { CASH, FX_POSITION, EFT_SUSPENSE, EXTERNAL_SETTLEMENT }

    private final AccountRepository accounts;
    private final Map<String, Long> cache = new ConcurrentHashMap<>();

    public InternalAccounts(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Long id(Purpose purpose, Currency currency) {
        String code = purpose.name() + "_" + currency.name();
        return cache.computeIfAbsent(code, c -> accounts.findByInternalCode(c)
                .orElseThrow(() -> new IllegalStateException("Missing internal account " + c))
                .getId());
    }
}
