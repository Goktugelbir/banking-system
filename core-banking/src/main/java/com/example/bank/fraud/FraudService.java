package com.example.bank.fraud;

import com.example.bank.account.Account;
import com.example.bank.account.AccountService;
import com.example.bank.common.BusinessException;
import com.example.bank.outbox.TransactionCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Fraud service: consumes TransactionCompleted events. Logically a separate service - it only
 * talks to the core through Kafka (in) and account status changes (out) - packaged in the same
 * deployable to keep the demo light.
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    private static final String CONSUMER = "fraud";
    private static final Set<String> SCREENED_TYPES = Set.of("TRANSFER", "EFT_OUT");

    private final FraudRules rules;
    private final FraudAlertRepository alerts;
    private final AccountService accountService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FraudService(FraudRules rules, FraudAlertRepository alerts, AccountService accountService,
                        JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.rules = rules;
        this.alerts = alerts;
        this.accountService = accountService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bank.outbox.topic}", groupId = "fraud-service", autoStartup = "${bank.fraud.enabled:true}")
    @Transactional
    public void onEvent(String payload) throws JsonProcessingException {
        TransactionCompletedEvent event = objectMapper.readValue(payload, TransactionCompletedEvent.class);
        // At-least-once delivery -> de-duplicate in the same transaction as the side effects.
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), CONSUMER);
        if (inserted == 0 || !SCREENED_TYPES.contains(event.type()) || event.fromAccountId() == null) {
            return;
        }

        List<FraudRules.Hit> hits = rules.evaluate(event);
        if (hits.isEmpty()) {
            return;
        }
        boolean freeze = false;
        for (FraudRules.Hit hit : hits) {
            alerts.save(new FraudAlert(event.fromAccountId(), event.transactionId(), hit.rule(), hit.action(),
                    hit.details()));
            freeze |= hit.action() == FraudAlert.Action.FREEZE_ACCOUNT;
            log.warn("Fraud rule {} hit for account {}: {}", hit.rule(), event.fromAccountId(), hit.details());
        }
        accountService.escalate(event.fromAccountId(), freeze ? Account.Status.FROZEN : Account.Status.UNDER_REVIEW);
    }

    @Transactional(readOnly = true)
    public List<FraudAlert> openAlerts() {
        return alerts.findByStatusOrderByCreatedAtDesc(FraudAlert.Status.OPEN);
    }

    /**
     * RELEASED: false positive - alert closed and, if no other open alerts remain, account re-activated.
     * CONFIRMED: real fraud - account stays/gets frozen.
     */
    @Transactional
    public FraudAlert resolve(Long alertId, FraudAlert.Status decision) {
        if (decision == FraudAlert.Status.OPEN) {
            throw BusinessException.badRequest("INVALID_DECISION", "Decision must be RELEASED or CONFIRMED");
        }
        FraudAlert alert = alerts.findById(alertId).orElseThrow(() -> BusinessException.notFound("Alert " + alertId));
        alert.resolve(decision);
        if (decision == FraudAlert.Status.CONFIRMED) {
            accountService.changeStatus(alert.getAccountId(), Account.Status.FROZEN);
        } else {
            boolean otherOpen = alerts.findByAccountIdOrderByCreatedAtDesc(alert.getAccountId()).stream()
                    .anyMatch(a -> a.getStatus() == FraudAlert.Status.OPEN && !a.getId().equals(alertId));
            if (!otherOpen) {
                accountService.changeStatus(alert.getAccountId(), Account.Status.ACTIVE);
            }
        }
        return alert;
    }
}
