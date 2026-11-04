package vn.techzone.application;

import vn.techzone.application.port.PaymentPort;
import vn.techzone.application.port.CheckoutWorkflowPort;
import vn.techzone.application.port.OrderPort;
import vn.techzone.domain.Payment;
import vn.techzone.domain.TechZoneModels.*;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;

/** Application orchestration for idempotent checkout, inventory reservation and payment outcomes. */
public final class CheckoutWorkflowService {
    public OrderPort.Result execute(CheckoutWorkflowPort store, String address, String paymentMethod,
                                    String promotionCode, PaymentVerdict requestedVerdict,
                                    String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            return store.checkoutResult(null, "Mã yêu cầu thanh toán không được để trống.", false);
        String key = idempotencyKey.trim();
        SalesOrder previous = store.orderForIdempotencyKey(key);
        if (previous != null) {
            if (!previous.customerId.equals(store.signedInCustomerId()))
                return store.checkoutResult(null, "Mã yêu cầu đã thuộc tài khoản khác.", false);
            boolean paid = previous.status == OrderStatus.CONFIRMED || previous.status == OrderStatus.PACKED
                    || previous.status == OrderStatus.SHIPPED
                    || previous.status == OrderStatus.DELIVERED;
            return store.checkoutResult(previous, "Yêu cầu này đã được xử lý: " + store.checkoutStatusLabel(previous.status) + ".", paid);
        }
        String validation = store.validateCart();
        if (!validation.isBlank()) return store.checkoutResult(null, validation, false);
        if (address == null || address.isBlank()) return store.checkoutResult(null, "Vui lòng nhập địa chỉ giao hàng.", false);
        if (paymentMethod == null || paymentMethod.isBlank() || requestedVerdict == null)
            return store.checkoutResult(null, "Vui lòng chọn phương thức và kết quả thanh toán.", false);

        List<CartItem> items = store.checkoutCartItems();
        long subtotal = store.cartSubtotal();
        long discount = store.promotionDiscount(promotionCode, subtotal);
        if (promotionCode != null && !promotionCode.isBlank() && discount == 0)
            return store.checkoutResult(null, "Mã khuyến mãi không hợp lệ hoặc đơn hàng chưa đủ điều kiện áp dụng.", false);

        LocalDateTime createdAt = LocalDateTime.now();
        long sequence = store.checkoutSequence();
        String number = String.format(Locale.ROOT, "TZO-%02d%02d-%04d", createdAt.getYear() % 100,
                createdAt.getMonthValue(), sequence);
        SalesOrder order = new SalesOrder("ORD-" + sequence, number, store.signedInCustomerId(),
                store.signedInName(), address.trim(), paymentMethod, key,
                promotionCode == null ? "" : promotionCode, createdAt);
        store.updateCurrentShippingAddress(address.trim());

        long remainingDiscount = discount, remainingSubtotal = subtotal;
        for (int i = 0; i < items.size(); i++) {
            CartItem item = items.get(i);
            ProductVariant product = store.product(item.variantId);
            long unitPrice = item.effectiveSnapshot(product.getEffectivePrice());
            long lineSubtotal = item.calculateLineTotal(unitPrice);
            long lineDiscount = i + 1 == items.size() ? remainingDiscount
                    : remainingSubtotal == 0 ? 0
                    : BigInteger.valueOf(remainingDiscount).multiply(BigInteger.valueOf(lineSubtotal))
                            .divide(BigInteger.valueOf(remainingSubtotal)).longValueExact();
            store.appendReservedCheckoutLine(order, item, unitPrice, lineDiscount);
            remainingDiscount -= lineDiscount;
            remainingSubtotal -= lineSubtotal;
        }
        store.refreshAfterCheckoutReservation();
        order.subtotal = subtotal;
        order.discount = discount;
        order.tax = Math.max(0, subtotal - discount) / 10;
        order.shippingFee = subtotal - discount >= 2_000_000 ? 0 : 35_000;
        order.total = subtotal - discount + order.tax + order.shippingFee;
        order.status = OrderStatus.PENDING_PAYMENT;
        store.stageCheckoutOrder(order);

        PaymentPort.Authorization authorization;
        try { authorization = store.authorizePayment(order.id, order.total, key, requestedVerdict); }
        catch (RuntimeException ex) { authorization = null; }
        PaymentVerdict finalVerdict = authorization == null || authorization.verdict() == null
                ? PaymentVerdict.UNKNOWN : authorization.verdict();
        if (finalVerdict == PaymentVerdict.AUTHORIZED
                && (authorization.verificationToken() == null || authorization.verificationToken().isBlank()))
            finalVerdict = PaymentVerdict.UNKNOWN;
        LocalDateTime authorizationTime = LocalDateTime.now();
        if (finalVerdict == PaymentVerdict.AUTHORIZED) try {
            order.confirm(store.paymentRecord(order, authorization.verificationToken(), "AUTHORIZED", authorizationTime), authorizationTime);
        } catch (IllegalStateException invalidAuthorization) { finalVerdict = PaymentVerdict.UNKNOWN; }

        if (finalVerdict == PaymentVerdict.AUTHORIZED) {
            store.completeCheckoutPayment(order);
            return store.checkoutResult(order, "Thanh toán được duyệt. Đơn đã xác nhận và chuyển sang danh sách lấy hàng.", true);
        }
        if (finalVerdict == PaymentVerdict.DECLINED) {
            store.rejectCheckoutPayment(order);
            return store.checkoutResult(order, "Giao dịch bị từ chối. Đơn chưa xác nhận và phần tồn kho đã được trả lại.", false);
        }
        store.deferCheckoutPayment(order);
        return store.checkoutResult(order, "Cổng thanh toán chưa trả kết quả cuối cùng. Đơn đang chờ đối soát; hệ thống giữ tồn kho và không xác nhận đơn.", false);
    }
}
