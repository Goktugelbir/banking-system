package com.example.bank.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId, Pageable pageable);

    List<LedgerEntry> findByTransactionIdOrderById(UUID transactionId);
}
