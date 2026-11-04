package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.PaymentVerdict;
import vn.techzone.domain.TechZoneModels.SalesOrder;

/** Checkout orchestration boundary; payment verification is provided by PaymentPort. */
public interface OrderPort {
    interface Result { SalesOrder order(); String message(); boolean confirmed(); }
    Result checkout(String address,String paymentMethod,String promotion,PaymentVerdict verdict,String idempotencyKey);
}
