package com.example.bank.fraud;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, Long> {

    List<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.Status status);

    List<FraudAlert> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
