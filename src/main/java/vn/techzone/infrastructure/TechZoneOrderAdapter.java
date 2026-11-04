package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.CheckoutWorkflowService;
import vn.techzone.application.port.CheckoutWorkflowPort;
import vn.techzone.domain.Payment;
import vn.techzone.domain.TechZoneModels.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Infrastructure adapter for checkout and order persistence operations. */
public final class TechZoneOrderAdapter implements CheckoutWorkflowPort {
    private final CheckoutWorkflowService workflow = new CheckoutWorkflowService();
    private final TechZoneStore store;
    public TechZoneOrderAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public Result checkout(String address,String payment,String promotion,PaymentVerdict verdict,String key) {
        return workflow.execute(this,address,payment,promotion,verdict,key);
    }
    @Override public SalesOrder orderForIdempotencyKey(String key) { return store.orderForIdempotencyKey(key); }
    @Override public String checkoutStatusLabel(OrderStatus status) { return TechZoneStore.localizedStatus(status); }
    @Override public String signedInCustomerId() { return store.signedInCustomerId(); }
    @Override public String signedInName() { return store.signedInName(); }
    @Override public String validateCart() { return store.validateCart(); }
    @Override public List<CartItem> checkoutCartItems() { return store.checkoutCartItems(); }
    @Override public long cartSubtotal() { return store.cartSubtotal(); }
    @Override public long promotionDiscount(String code,long subtotal) { return store.promotionDiscount(code,subtotal); }
    @Override public long checkoutSequence() { return store.checkoutSequence(); }
    @Override public ProductVariant product(String id) { return store.product(id); }
    @Override public void updateCurrentShippingAddress(String address) { store.updateCurrentShippingAddress(address); }
    @Override public void appendReservedCheckoutLine(SalesOrder order,CartItem item,long price,long discount) { store.appendReservedCheckoutLine(order,item,price,discount); }
    @Override public void refreshAfterCheckoutReservation() { store.refreshAfterCheckoutReservation(); }
    @Override public void stageCheckoutOrder(SalesOrder order) { store.stageCheckoutOrder(order); }
    @Override public vn.techzone.application.port.PaymentPort.Authorization authorizePayment(String id,long total,String key,PaymentVerdict verdict) {
        return store.authorizePayment(id,total,key,verdict);
    }
    @Override public Payment paymentRecord(SalesOrder order,String reference,String status,LocalDateTime at) { return store.paymentRecord(order,reference,status,at); }
    @Override public void completeCheckoutPayment(SalesOrder order) { store.completeCheckoutPayment(order); }
    @Override public void rejectCheckoutPayment(SalesOrder order) { store.rejectCheckoutPayment(order); }
    @Override public void deferCheckoutPayment(SalesOrder order) { store.deferCheckoutPayment(order); }
    @Override public Result checkoutResult(SalesOrder order,String message,boolean confirmed) { return new TechZoneStore.CheckoutResult(order,message,confirmed); }
}
