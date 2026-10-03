-- The bank's own general-ledger accounts, one per purpose and currency.
-- CASH:                 counterpart of cash deposits / withdrawals
-- FX_POSITION:          the bank's currency position for exchanges
-- EFT_SUSPENSE:         funds reserved for an in-flight outgoing EFT (the "hold")
-- EXTERNAL_SETTLEMENT:  nostro - money that has left to other banks
INSERT INTO accounts (account_number, type, internal_code, currency, balance, status)
SELECT 'INT-' || p.purpose || '-' || c.code, 'INTERNAL', p.purpose || '_' || c.code, c.code, 0, 'ACTIVE'
FROM (VALUES ('CASH'), ('FX_POSITION'), ('EFT_SUSPENSE'), ('EXTERNAL_SETTLEMENT')) AS p(purpose)
CROSS JOIN (VALUES ('TRY'), ('USD'), ('EUR'), ('GBP'), ('BTC'), ('ETH')) AS c(code);
