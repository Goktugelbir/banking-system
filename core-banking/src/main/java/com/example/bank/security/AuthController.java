package com.example.bank.security;

import com.example.bank.common.BusinessException;
import com.example.bank.customer.Customer;
import com.example.bank.customer.CustomerRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {

    public record RegisterRequest(@Email @NotBlank String email,
                                  @NotBlank @Size(min = 8, max = 100) String password,
                                  @NotBlank String fullName) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds, Long customerId) {
    }

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(CustomerRepository customers, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(summary = "Register a new customer")
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        if (customers.existsByEmailIgnoreCase(req.email())) {
            throw BusinessException.conflict("EMAIL_TAKEN", "Email already registered");
        }
        Customer c = customers.save(new Customer(req.email().toLowerCase(), passwordEncoder.encode(req.password()),
                req.fullName(), Customer.Role.CUSTOMER));
        return token(c);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and receive a JWT")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        Customer c = customers.findByEmailIgnoreCase(req.email())
                .filter(found -> passwordEncoder.matches(req.password(), found.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                        "Invalid email or password"));
        return token(c);
    }

    @GetMapping("/me")
    @Operation(summary = "Current principal")
    public AuthUser me(@AuthenticationPrincipal AuthUser user) {
        return user;
    }

    private TokenResponse token(Customer c) {
        return new TokenResponse(jwtService.issue(c), "Bearer", jwtService.ttl().toSeconds(), c.getId());
    }
}
