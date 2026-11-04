package vn.techzone.application.port;

/** Boundary for dispatch and delivery-provider events. */
public interface DeliveryPort {
    record DispatchRequest(String orderNumber, String carrier, String recipient, String address,
                           int weightGrams, String dimensions, long declaredValue) { }

    String createTrackingCode(DispatchRequest request);

    boolean verifyCallback(String trackingCode, String callbackId, String milestone);
}
