package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.PaymentVerdict;

/** Boundary for provider authorization and verified final callbacks. */
public interface PaymentPort {
    record Authorization(PaymentVerdict verdict, String verificationToken) { }

    Authorization authorize(String orderId, long amount, String idempotencyKey, PaymentVerdict simulatedOutcome);

    boolean verifyCallback(String orderId, String callbackId, PaymentVerdict verdict, String verificationToken);
}
