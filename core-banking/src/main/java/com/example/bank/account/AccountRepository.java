package com.example.bank.account;

import com.example.bank.common.Currency;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** SELECT ... FOR UPDATE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    /** Currency is immutable, so it can be read without a lock (and without loading the entity). */
    @Query("select a.currency from Account a where a.id = :id")
    Optional<Currency> findCurrency(@Param("id") Long id);

    List<Account> findByCustomerIdOrderById(Long customerId);

    Optional<Account> findByInternalCode(String internalCode);

    Optional<Account> findByAccountNumber(String accountNumber);
}
