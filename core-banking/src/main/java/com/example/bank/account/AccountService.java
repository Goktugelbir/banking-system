package com.example.bank.account;

import com.example.bank.common.BusinessException;
import com.example.bank.common.Currency;
import com.example.bank.ledger.LedgerEntry;
import com.example.bank.ledger.LedgerEntryRepository;
import com.example.bank.security.AuthUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;

    public AccountService(AccountRepository accounts, LedgerEntryRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * Customers only ever see their own accounts. A foreign account answers 404, not 403,
     * so the API does not reveal which account ids exist.
     */
    public static void requireAccess(Account account, AuthUser user) {
        if (!user.isAdmin() && !account.isOwnedBy(user.id())) {
            throw BusinessException.notFound("Account " + account.getId());
        }
    }

    @Transactional
    public Account open(AuthUser user, Currency currency) {
        return accounts.save(new Account(user.id(), newAccountNumber(), currency));
    }

    @Transactional(readOnly = true)
    public List<Account> mine(AuthUser user) {
        return accounts.findByCustomerIdOrderById(user.id());
    }

    @Transactional(readOnly = true)
    public Account get(AuthUser user, Long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> BusinessException.notFound("Account " + accountId));
        requireAccess(account, user);
        return account;
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> statement(AuthUser user, Long accountId, int limit) {
        get(user, accountId);
        return ledger.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(0, Math.min(limit, 500)));
    }

    /** Used by fraud detection and admins. Status changes take the row lock like any balance change. */
    @Transactional
    public Account changeStatus(Long accountId, Account.Status status) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> BusinessException.notFound("Account " + accountId));
        if (account.getType() != Account.Type.CUSTOMER) {
            throw BusinessException.badRequest("INTERNAL_ACCOUNT", "Internal accounts cannot change status");
        }
        account.changeStatus(status);
        return account;
    }

    /** Only ever raises severity (ACTIVE < UNDER_REVIEW < FROZEN); a review never un-freezes an account. */
    @Transactional
    public Account escalate(Long accountId, Account.Status target) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> BusinessException.notFound("Account " + accountId));
        if (account.getType() == Account.Type.CUSTOMER && target.ordinal() > account.getStatus().ordinal()) {
            account.changeStatus(target);
        }
        return account;
    }

    /** TR + 24 digits: IBAN-shaped, not a checksum-valid IBAN. */
    private static String newAccountNumber() {
        StringBuilder sb = new StringBuilder("TR");
        for (int i = 0; i < 24; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
