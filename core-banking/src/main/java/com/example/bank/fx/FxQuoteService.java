package com.example.bank.fx;

import com.example.bank.account.Account;
import com.example.bank.account.AccountService;
import com.example.bank.common.BusinessException;
import com.example.bank.config.BankProperties;
import com.example.bank.idempotency.IdempotencyService;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService;
import com.example.bank.transaction.TransactionResponse;
import com.example.bank.transaction.TransactionalExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Two-step FX: (1) quote - the customer sees a firm rate valid for {@code quote-ttl} (30s);
 * (2) accept - the exchange is executed at exactly that rate, regardless of market moves in between.
 */
@Service
public class FxQuoteService {

    private static final String KEY_PREFIX = "fx:quote:";

    private final FxRateService rates;
    private final AccountService accounts;
    private final MoneyMovementService money;
    private final IdempotencyService idempotency;
    private final TransactionalExecutor executor;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BankProperties.Fx config;

    public FxQuoteService(FxRateService rates, AccountService accounts, MoneyMovementService money,
                          IdempotencyService idempotency, TransactionalExecutor executor, StringRedisTemplate redis,
                          ObjectMapper objectMapper, BankProperties props) {
        this.rates = rates;
        this.accounts = accounts;
        this.money = money;
        this.idempotency = idempotency;
        this.executor = executor;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.config = props.fx();
    }

    public FxQuote createQuote(AuthUser user, Long fromAccountId, Long toAccountId, BigDecimal sellAmount) {
        Account from = accounts.get(user, fromAccountId);
        Account to = accounts.get(user, toAccountId);
        if (from.getCurrency() == to.getCurrency()) {
            throw BusinessException.badRequest("SAME_CURRENCY", "Use a normal transfer for same-currency accounts");
        }
        BigDecimal sell = from.getCurrency().normalize(sellAmount);
        FxRateService.Rate mid = rates.rate(from.getCurrency(), to.getCurrency());

        // The customer gets the mid rate minus the bank's spread.
        BigDecimal customerRate = mid.rate().multiply(BigDecimal.ONE.subtract(config.spread()), MathContext.DECIMAL128)
                .setScale(12, RoundingMode.DOWN);
        BigDecimal buy = to.getCurrency().roundDown(sell.multiply(customerRate, MathContext.DECIMAL128));
        if (buy.signum() <= 0) {
            throw BusinessException.badRequest("AMOUNT_TOO_SMALL", "Amount is too small to exchange");
        }

        FxQuote quote = new FxQuote(UUID.randomUUID(), user.id(), from.getId(), to.getId(), from.getCurrency(),
                to.getCurrency(), sell, buy, customerRate, mid.rate(), Instant.now().plus(config.quoteTtl()));
        try {
            redis.opsForValue().set(KEY_PREFIX + quote.id(), objectMapper.writeValueAsString(quote), config.quoteTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return quote;
    }

    public TransactionResponse accept(AuthUser user, UUID quoteId, String idempotencyKey) {
        try {
            TransactionResponse response = idempotency.execute(user.id(), idempotencyKey, Map.of("quoteId", quoteId),
                    ctx -> {
                        FxQuote quote = activeQuote(user, quoteId);
                        return executor.execute(() -> money.executeFx(ctx, user, quote));
                    },
                    money::load).value();
            redis.delete(KEY_PREFIX + quoteId);
            return response;
        } catch (DuplicateKeyException e) {
            // transactions.quote_id is UNIQUE: someone already consumed this quote with another key.
            throw BusinessException.conflict("QUOTE_ALREADY_USED", "This quote has already been accepted");
        }
    }

    private FxQuote activeQuote(AuthUser user, UUID quoteId) {
        String json = redis.opsForValue().get(KEY_PREFIX + quoteId);
        FxQuote quote;
        try {
            quote = json == null ? null : objectMapper.readValue(json, FxQuote.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (quote == null || quote.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(HttpStatus.GONE, "QUOTE_EXPIRED", "Quote expired; request a new one");
        }
        if (!quote.customerId().equals(user.id())) {
            throw BusinessException.notFound("Quote " + quoteId);
        }
        return quote;
    }
}
