package vn.techzone.infrastructure;

import vn.techzone.application.port.PaymentPort;
import vn.techzone.domain.TechZoneModels.PaymentVerdict;

import java.util.LinkedHashMap;
import java.util.Map;

/** Local provider simulation. Token format checking is not real provider verification. */
public final class MockPaymentGateway implements PaymentPort {
    private final Map<String, Authorization> requests = new LinkedHashMap<>();

    @Override public synchronized Authorization authorize(String orderId, long amount, String key, PaymentVerdict outcome) {
        return requests.computeIfAbsent(key, ignored -> new Authorization(outcome,
                outcome == PaymentVerdict.AUTHORIZED ? "MOCK-AUTH-" + orderId : ""));
    }

    @Override public boolean verifyCallback(String orderId, String callbackId, PaymentVerdict verdict, String token) {
        return orderId != null && !orderId.isBlank() && callbackId != null && !callbackId.isBlank()
                && verdict != null && verdict != PaymentVerdict.UNKNOWN
                && (verdict != PaymentVerdict.AUTHORIZED || (token != null && token.trim().startsWith("MOCK-")));
    }
}
