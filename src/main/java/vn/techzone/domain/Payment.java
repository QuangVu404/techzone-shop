package vn.techzone.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** Payment record bound to an order; gateway truth still comes from PaymentPort. */
public record Payment(String id,String orderId,String method,String providerReference,String idempotencyKey,
                      long amountMinor,String currency,String status,LocalDateTime authorizedAt,LocalDateTime capturedAt,LocalDateTime updatedAt) {
    public Payment {
        Objects.requireNonNull(id);Objects.requireNonNull(orderId);Objects.requireNonNull(method);
        providerReference=providerReference==null?"":providerReference;
        idempotencyKey=idempotencyKey==null?"":idempotencyKey;
        currency=currency==null?"VND":currency;status=status==null?"UNKNOWN":status;
        if(amountMinor<0) throw new IllegalArgumentException("Payment amount cannot be negative");
    }
    /** Checks persisted authorization data; it does not replace a gateway callback verification. */
    public boolean verifyAuthorization() { return "AUTHORIZED".equals(status) && !providerReference.isBlank() && authorizedAt!=null; }
}
