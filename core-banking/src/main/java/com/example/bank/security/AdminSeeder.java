package com.example.bank.security;

import com.example.bank.config.BankProperties;
import com.example.bank.customer.Customer;
import com.example.bank.customer.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final BankProperties props;

    public AdminSeeder(CustomerRepository customers, PasswordEncoder passwordEncoder, BankProperties props) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!customers.existsByEmailIgnoreCase(props.admin().email())) {
            customers.save(new Customer(props.admin().email(), passwordEncoder.encode(props.admin().password()),
                    "Bank Administrator", Customer.Role.ADMIN));
            log.info("Seeded admin user {}", props.admin().email());
        }
    }
}
