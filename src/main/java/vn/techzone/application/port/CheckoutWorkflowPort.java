package vn.techzone.application.port;

import vn.techzone.domain.Payment;
import vn.techzone.domain.TechZoneModels.*;
import java.time.LocalDateTime;
import java.util.List;

/** Checkout data and state commands used by the application workflow, implemented by an infrastructure adapter. */
public interface CheckoutWorkflowPort extends OrderPort {
    SalesOrder orderForIdempotencyKey(String key);
    String checkoutStatusLabel(OrderStatus status);
    String signedInCustomerId();
    String signedInName();
    String validateCart();
    List<CartItem> checkoutCartItems();
    long cartSubtotal();
    long promotionDiscount(String code,long subtotal);
    long checkoutSequence();
    ProductVariant product(String variantId);
    void updateCurrentShippingAddress(String address);
    void appendReservedCheckoutLine(SalesOrder order,CartItem item,long unitPrice,long lineDiscount);
    void refreshAfterCheckoutReservation();
    void stageCheckoutOrder(SalesOrder order);
    PaymentPort.Authorization authorizePayment(String orderId,long total,String key,PaymentVerdict verdict);
    Payment paymentRecord(SalesOrder order,String reference,String status,LocalDateTime authorizedAt);
    void completeCheckoutPayment(SalesOrder order);
    void rejectCheckoutPayment(SalesOrder order);
    void deferCheckoutPayment(SalesOrder order);
    Result checkoutResult(SalesOrder order,String message,boolean confirmed);
}
