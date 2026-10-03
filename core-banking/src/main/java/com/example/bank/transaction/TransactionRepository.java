package com.example.bank.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<BankTransaction, UUID> {

    @Query("""
            select t from BankTransaction t
            where t.fromAccountId = :accountId or t.toAccountId = :accountId
            order by t.createdAt desc
            """)
    List<BankTransaction> findByAccount(@Param("accountId") Long accountId, Pageable pageable);
}
