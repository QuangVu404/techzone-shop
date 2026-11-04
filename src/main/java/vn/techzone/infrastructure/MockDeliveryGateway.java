package vn.techzone.infrastructure;

import vn.techzone.application.port.DeliveryPort;

import java.util.Set;

/** Local carrier simulation; no callback signatures or network calls are performed. */
public final class MockDeliveryGateway implements DeliveryPort {
    @Override public String createTrackingCode(DispatchRequest request) {
        if (request == null || request.orderNumber() == null || request.orderNumber().isBlank()
                || request.carrier() == null || request.carrier().isBlank() || request.address() == null
                || request.address().isBlank() || request.weightGrams() <= 0 || request.dimensions() == null
                || request.dimensions().isBlank()) return "";
        return "TZVN" + (900100000000L + Math.abs(request.orderNumber().hashCode() % 899999999));
    }

    @Override public boolean verifyCallback(String trackingCode, String callbackId, String milestone) {
        return trackingCode != null && !trackingCode.isBlank() && callbackId != null && !callbackId.isBlank()
                && Set.of("Đang trung chuyển", "Đang giao tới bạn", "Đã giao thành công").contains(milestone);
    }
}
