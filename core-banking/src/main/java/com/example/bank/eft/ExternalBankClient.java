package com.example.bank.eft;

import com.example.bank.common.Currency;
import com.example.bank.config.BankProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HTTP client of the simulated external bank. The {@code reference} (our saga id) is the
 * external bank's idempotency key, so re-sending or cancelling is always safe.
 */
@Component
public class ExternalBankClient {

    public enum ExternalStatus { ACCEPTED, REJECTED, CANCELLED }

    public record TransferRequest(String reference, String iban, BigDecimal amount, Currency currency) {
    }

    public record TransferResponse(String reference, ExternalStatus status, String reason) {
    }

    private final RestClient http;

    public ExternalBankClient(BankProperties props) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(props.eft().timeout());
        this.http = RestClient.builder()
                .baseUrl(props.eft().externalBankUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Throws on timeout / connection error: the outcome is then UNKNOWN (the other bank may have
     * processed it and only the response got lost). Throws CallNotPermittedException without
     * sending anything when the circuit is open.
     */
    @CircuitBreaker(name = "externalBank")
    public TransferResponse send(TransferRequest request) {
        return http.post().uri("/api/v1/transfers").body(request).retrieve().body(TransferResponse.class);
    }

    /**
     * Asks the external bank to cancel. Its answer is authoritative: ACCEPTED means it had already
     * executed the transfer (we must complete), CANCELLED/REJECTED means it never will (we compensate).
     */
    public TransferResponse cancel(String reference) {
        return http.post().uri("/api/v1/transfers/{ref}/cancel", reference).retrieve().body(TransferResponse.class);
    }
}
