package com.example.bank.eft;

import com.example.bank.config.BankProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * In-process stand-in for the external-bank service with the same semantics (first writer wins per
 * reference), but scriptable: tests can force an outcome or switch to random chaos.
 */
public class FakeExternalBank extends ExternalBankClient {

    public enum Mode { ACCEPT, REJECT, TIMEOUT_EXECUTED, TIMEOUT_NOT_EXECUTED, DOWN, CHAOS }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakeExternalBank fakeExternalBank(BankProperties props) {
            return new FakeExternalBank(props);
        }
    }

    private final Map<String, ExternalStatus> ledger = new ConcurrentHashMap<>();
    private final Set<String> lateDeliveries = ConcurrentHashMap.newKeySet();
    private volatile Mode mode = Mode.ACCEPT;
    private volatile boolean cancelAvailable = true;

    public FakeExternalBank(BankProperties props) {
        super(props);
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void cancelAvailable(boolean available) {
        this.cancelAvailable = available;
    }

    public void reset() {
        ledger.clear();
        lateDeliveries.clear();
        mode = Mode.ACCEPT;
        cancelAvailable = true;
    }

    @Override
    public TransferResponse send(TransferRequest request) {
        Mode effective = mode;
        if (effective == Mode.CHAOS) {
            double r = ThreadLocalRandom.current().nextDouble();
            effective = r < 0.10 ? Mode.REJECT
                    : r < 0.20 ? Mode.TIMEOUT_EXECUTED
                    : r < 0.30 ? Mode.TIMEOUT_NOT_EXECUTED
                    : Mode.ACCEPT;
        }
        String ref = request.reference();
        return switch (effective) {
            case ACCEPT -> response(ref, decide(ref, ExternalStatus.ACCEPTED));
            case REJECT -> response(ref, decide(ref, ExternalStatus.REJECTED));
            case TIMEOUT_EXECUTED -> {
                decide(ref, ExternalStatus.ACCEPTED);
                throw new ResourceAccessException("Read timed out (but executed)");
            }
            case TIMEOUT_NOT_EXECUTED -> {
                lateDeliveries.add(ref);
                throw new ResourceAccessException("Read timed out (not executed yet)");
            }
            case DOWN -> throw new ResourceAccessException("Connection refused");
            default -> throw new IllegalStateException();
        };
    }

    @Override
    public TransferResponse cancel(String reference) {
        if (!cancelAvailable) {
            throw new ResourceAccessException("Connection refused");
        }
        return response(reference, decide(reference, ExternalStatus.CANCELLED));
    }

    /** The slow requests finally arrive - after a cancel they must lose. */
    public void deliverLateRequests() {
        lateDeliveries.forEach(ref -> decide(ref, ExternalStatus.ACCEPTED));
        lateDeliveries.clear();
    }

    public Set<String> acceptedReferences() {
        return ledger.entrySet().stream()
                .filter(e -> e.getValue() == ExternalStatus.ACCEPTED)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private ExternalStatus decide(String ref, ExternalStatus proposed) {
        return ledger.merge(ref, proposed, (current, ignored) -> current);
    }

    private static TransferResponse response(String ref, ExternalStatus status) {
        return new TransferResponse(ref, status, null);
    }
}
