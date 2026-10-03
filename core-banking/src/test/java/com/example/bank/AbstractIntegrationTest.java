package com.example.bank;

import com.example.bank.account.Account;
import com.example.bank.account.AccountRepository;
import com.example.bank.account.AccountService;
import com.example.bank.common.Currency;
import com.example.bank.customer.Customer;
import com.example.bank.customer.CustomerRepository;
import com.example.bank.eft.FakeExternalBank;
import com.example.bank.ledger.ReconciliationService;
import com.example.bank.security.AuthUser;
import com.example.bank.transaction.MoneyMovementService;
import com.example.bank.transaction.MoneyMovementService.CashCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres, Redis and Kafka in Docker. The containers are started once per JVM (singleton
 * pattern) and shared by every test class and Spring context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeExternalBank.Config.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    protected CustomerRepository customers;
    @Autowired
    protected AccountRepository accountRepository;
    @Autowired
    protected AccountService accountService;
    @Autowired
    protected MoneyMovementService money;
    @Autowired
    protected ReconciliationService reconciliation;

    protected AuthUser newCustomer() {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        Customer c = customers.save(new Customer(email, "{noop}x", "Test User", Customer.Role.CUSTOMER));
        return new AuthUser(c.getId(), email, Customer.Role.CUSTOMER);
    }

    protected AuthUser admin() {
        Customer c = customers.findByEmailIgnoreCase("admin@bank.local").orElseThrow();
        return new AuthUser(c.getId(), c.getEmail(), Customer.Role.ADMIN);
    }

    protected Account openAccount(AuthUser owner, Currency currency, String initialBalance) {
        Account account = accountService.open(owner, currency);
        if (new BigDecimal(initialBalance).signum() > 0) {
            money.deposit(admin(), UUID.randomUUID().toString(),
                    new CashCommand(account.getId(), new BigDecimal(initialBalance), "seed"));
        }
        return account;
    }

    protected BigDecimal balance(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    protected void assertLedgerConsistent() {
        ReconciliationService.Report report = reconciliation.reconcile();
        assertThat(report.accountMismatches()).as("cached balance == sum(ledger)").isEmpty();
        assertThat(report.currencyImbalances()).as("double-entry: ledger nets to zero").isEmpty();
    }
}
