package com.example.bank.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Proves the two sources of truth agree:
 * <ol>
 *   <li>every account's cached balance equals the signed sum of its ledger entries</li>
 *   <li>the whole ledger nets to zero per currency (double-entry invariant)</li>
 * </ol>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record AccountMismatch(Long accountId, BigDecimal cachedBalance, BigDecimal ledgerBalance) {
    }

    public record CurrencyImbalance(String currency, BigDecimal net) {
    }

    public record Report(boolean consistent, List<AccountMismatch> accountMismatches,
                         List<CurrencyImbalance> currencyImbalances) {
    }

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Report reconcile() {
        List<AccountMismatch> mismatches = jdbc.query("""
                SELECT a.id, a.balance,
                       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS ledger
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                """, (rs, i) -> new AccountMismatch(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));

        List<CurrencyImbalance> imbalances = jdbc.query("""
                SELECT currency, SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS net
                FROM ledger_entries
                GROUP BY currency
                HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) <> 0
                """, (rs, i) -> new CurrencyImbalance(rs.getString(1), rs.getBigDecimal(2)));

        return new Report(mismatches.isEmpty() && imbalances.isEmpty(), mismatches, imbalances);
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void scheduledCheck() {
        Report report = reconcile();
        if (!report.consistent()) {
            log.error("LEDGER RECONCILIATION FAILED: {}", report);
        }
    }
}
