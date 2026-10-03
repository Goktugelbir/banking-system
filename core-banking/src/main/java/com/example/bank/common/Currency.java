package com.example.bank.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Supported currencies. Each one has its own minor-unit scale; amounts are always
 * normalised to that scale so 10.1 TRY and 10.10 TRY are the same value.
 */
public enum Currency {
    TRY(2, false),
    USD(2, false),
    EUR(2, false),
    GBP(2, false),
    BTC(8, true),
    ETH(8, true);

    private final int scale;
    private final boolean crypto;

    Currency(int scale, boolean crypto) {
        this.scale = scale;
        this.crypto = crypto;
    }

    public int scale() {
        return scale;
    }

    public boolean isCrypto() {
        return crypto;
    }

    /** Rejects amounts that carry more precision than the currency allows. */
    public BigDecimal normalize(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw BusinessException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        }
        try {
            return amount.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw BusinessException.badRequest("INVALID_AMOUNT",
                    name() + " supports at most " + scale + " decimal places");
        }
    }

    /** Rounds a computed amount (e.g. an FX result) down: the bank never pays out money it does not have. */
    public BigDecimal roundDown(BigDecimal amount) {
        return amount.setScale(scale, RoundingMode.DOWN);
    }
}
