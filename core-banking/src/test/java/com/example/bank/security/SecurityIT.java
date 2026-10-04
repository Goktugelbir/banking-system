package com.example.bank.security;

import com.example.bank.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void customerSeesOnlyOwnAccounts() throws Exception {
        String alice = register();
        String bob = register();

        long aliceAccount = json.readTree(mvc.perform(post("/api/accounts").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"TRY\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/accounts/" + aliceAccount).header("Authorization", alice))
                .andExpect(status().isOk());
        // Bob gets 404, not 403: the API does not even confirm the account exists.
        mvc.perform(get("/api/accounts/" + aliceAccount).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/" + aliceAccount + "/statement").header("Authorization", bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonPositiveLimitIsClampedInsteadOfFailing() throws Exception {
        String alice = register();
        long account = json.readTree(mvc.perform(post("/api/accounts").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"TRY\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(get("/api/accounts/" + account + "/statement?limit=0").header("Authorization", alice))
                .andExpect(status().isOk());
        mvc.perform(get("/api/accounts/" + account + "/transactions?limit=-5").header("Authorization", alice))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotUseAdminEndpoints() throws Exception {
        String customer = register();
        mvc.perform(get("/api/admin/reconciliation").header("Authorization", customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReconcile() throws Exception {
        String admin = login("admin@bank.local", "admin12345");
        mvc.perform(get("/api/admin/reconciliation").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void transferRequiresIdempotencyKey() throws Exception {
        String customer = register();
        mvc.perform(post("/api/transfers").header("Authorization", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    private String register() throws Exception {
        String email = "u" + UUID.randomUUID() + "@test.local";
        JsonNode body = json.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"fullName\":\"T\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("accessToken").asText();
    }

    private String login(String email, String password) throws Exception {
        JsonNode body = json.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return "Bearer " + body.get("accessToken").asText();
    }
}
