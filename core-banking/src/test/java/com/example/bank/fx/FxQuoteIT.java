package com.example.bank.fx;

import com.example.bank.AbstractIntegrationTest;
import com.example.bank.account.Account;
import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the static rate source: 1 USD = 40 TRY, spread 0.5%. */
class FxQuoteIT extends AbstractIntegrationTest {

    @Autowired
    FxQuoteService quotes;

    @Test
    void quoteIsExecutedAtTheQuotedRate() {
        AuthUser user = newCustomer();
        Account tryAccount = openAccount(user, Currency.TRY, "4000.00");
        Account usdAccount = openAccount(user, Currency.USD, "0");

        FxQuote quote = quotes.createQuote(user, tryAccount.getId(), usdAccount.getId(), new BigDecimal("4000.00"));
        // mid 0.025 USD per TRY, minus 0.5% spread -> 0.024875 -> 99.50 USD
        assertThat(quote.buyAmount()).isEqualByComparingTo("99.50");

        TransactionResponse tx = quotes.accept(user, quote.id(), UUID.randomUUID().toString());

        assertThat(tx.counterAmount()).isEqualByComparingTo("99.50");
        assertThat(balance(tryAccount.getId())).isEqualByComparingTo("0");
        assertThat(balance(usdAccount.getId())).isEqualByComparingTo("99.50");
        assertLedgerConsistent();
    }

    @Test
    void quoteCanBeUsedOnlyOnce() {
        AuthUser user = newCustomer();
        Account tryAccount = openAccount(user, Currency.TRY, "10000.00");
        Account usdAccount = openAccount(user, Currency.USD, "0");
        FxQuote quote = quotes.createQuote(user, tryAccount.getId(), usdAccount.getId(), new BigDecimal("400.00"));
        String key = UUID.randomUUID().toString();

        TransactionResponse first = quotes.accept(user, quote.id(), key);
        // Same idempotency key -> replay of the same transaction
        assertThat(quotes.accept(user, quote.id(), key).id()).isEqualTo(first.id());
        // New key -> quote is gone
        assertThatThrownBy(() -> quotes.accept(user, quote.id(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.GONE);
        assertThat(balance(usdAccount.getId())).isEqualByComparingTo("9.95");
    }

    @Test
    void cryptoQuoteUsesEightDecimals() {
        AuthUser user = newCustomer();
        Account usd = openAccount(user, Currency.USD, "1000.00");
        Account btc = openAccount(user, Currency.BTC, "0");

        FxQuote quote = quotes.createQuote(user, usd.getId(), btc.getId(), new BigDecimal("1000.00"));
        // 1000 / 100000 * 0.995 = 0.00995 BTC
        assertThat(quote.buyAmount()).isEqualByComparingTo("0.00995000");
        assertThat(quote.buyAmount().scale()).isEqualTo(8);
    }

    @Test
    void unknownOrExpiredQuoteIsGone() {
        AuthUser user = newCustomer();
        assertThatThrownBy(() -> quotes.accept(user, UUID.randomUUID(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("QUOTE_EXPIRED");
    }

    @Test
    void customerCannotQuoteOnSomeoneElsesAccount() {
        AuthUser alice = newCustomer();
        AuthUser mallory = newCustomer();
        Account aliceTry = openAccount(alice, Currency.TRY, "1000.00");
        Account malloryUsd = openAccount(mallory, Currency.USD, "0");

        assertThatThrownBy(() -> quotes.createQuote(mallory, aliceTry.getId(), malloryUsd.getId(), BigDecimal.TEN))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
    }
}
