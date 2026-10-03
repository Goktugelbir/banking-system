package com.example.bank.security;

import com.example.bank.customer.Customer;

/** The authenticated principal, rebuilt from JWT claims on every request (no DB lookup). */
public record AuthUser(Long id, String email, Customer.Role role) {

    public boolean isAdmin() {
        return role == Customer.Role.ADMIN;
    }
}
