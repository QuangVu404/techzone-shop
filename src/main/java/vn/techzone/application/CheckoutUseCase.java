package vn.techzone.application;

import vn.techzone.application.port.OrderPort;
import vn.techzone.application.port.CheckoutWorkflowPort;
import vn.techzone.domain.TechZoneModels.PaymentVerdict;
import java.util.Objects;

/** Initiates checkout through the order boundary. */
public final class CheckoutUseCase {
    private final OrderPort orders;
    private final CheckoutWorkflowService workflow = new CheckoutWorkflowService();
    public CheckoutUseCase(OrderPort orders) { this.orders=Objects.requireNonNull(orders); }
    public OrderPort.Result execute(String address,String payment,String promotion,PaymentVerdict verdict,String key) {
        if (orders instanceof CheckoutWorkflowPort context)
            return workflow.execute(context,address,payment,promotion,verdict,key);
        return orders.checkout(address,payment,promotion,verdict,key);
    }
}
