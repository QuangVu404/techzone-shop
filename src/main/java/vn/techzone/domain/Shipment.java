package vn.techzone.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** Shipment tracking record; delivery is valid only after a recorded delivered state and time. */
public record Shipment(String id,String orderId,String trackingCode,String carrier,long weightGrams,String dimensions,
                       LocalDateTime shippedAt,String status,LocalDateTime deliveredAt) {
    public Shipment {
        Objects.requireNonNull(id);Objects.requireNonNull(orderId);trackingCode=trackingCode==null?"":trackingCode;
        carrier=carrier==null?"":carrier;dimensions=dimensions==null?"":dimensions;status=status==null?"":status;
        if(weightGrams<0) throw new IllegalArgumentException("Shipment weight cannot be negative");
    }
    public boolean verifyDelivery() { return "DELIVERED".equals(status) && deliveredAt!=null; }
}
