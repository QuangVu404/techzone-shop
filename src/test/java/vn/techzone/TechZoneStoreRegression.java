package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.domain.TechZoneModels.*;
import vn.techzone.application.port.PaymentPort;
import vn.techzone.application.port.DeliveryPort;
import vn.techzone.infrastructure.MockPaymentGateway;
import vn.techzone.infrastructure.MockDeliveryGateway;
import vn.techzone.infrastructure.LocalAiEngine;
import vn.techzone.infrastructure.DemoStoreRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Business regression checks for the demo domain; no external services are contacted. */
public final class TechZoneStoreRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void login(TechZoneStore store, String email, String password) {
        check(store.authenticateCustomer(email, password.toCharArray()).success(), "Đăng nhập thất bại: " + email);
    }

    private static void accountIsolation() {
        TechZoneStore store = new TechZoneStore();
        store.addToCart("CPU-14600K", 1);
        check(store.registerCustomer("Khách mới", "new@example.com", "Password123!".toCharArray()).success(), "Đăng ký");
        login(store, "new@example.com", "Password123!");
        String newId = store.signedInCustomerId();
        check(!newId.equals(TechZoneStore.CUSTOMER_ID), "Mã khách phải riêng");
        check(store.cart().isEmpty(), "Giỏ của khách mới phải riêng");
        check(store.signedInAddress().isBlank(), "Khách mới không được nhận địa chỉ mẫu");
        store.addToCart("GPU-4060", 1);
        TechZoneStore.CheckoutResult order = store.checkout("Địa chỉ khách mới", "Ví mock", "", PaymentVerdict.AUTHORIZED, "order-new-user");
        check(order.confirmed() && order.order().customerId.equals(newId), "Đơn phải gắn khách mới");
        check(!store.createCurrentCustomerRma("SN-TZ-PHONES24-0001", "Lỗi màn hình").startsWith("Đã tạo"), "Không cho tạo RMA cho thiết bị khách khác");
        check(!store.createRma("SN-TZ-PHONES24-0001", TechZoneStore.CUSTOMER_ID, "Lỗi màn hình").startsWith("Đã tạo"), "Không thể giả ID chủ sở hữu để tạo RMA");
        check(!store.createStaffRma("SN-TZ-PHONES24-0001", TechZoneStore.CUSTOMER_ID, "Lỗi màn hình", Role.CUSTOMER).startsWith("Đã tạo"), "Khách không thể tiếp nhận RMA thay người khác");
        check(!store.addReview("PHONE-S24", "Tốt", 5).startsWith("Đã nhận"), "Không cho đánh giá đơn khách khác");
        check(store.notifications(Role.CUSTOMER).stream().anyMatch(n -> n.message.contains(order.order().number)), "Thông báo phải tới đúng khách");
        login(store, TechZoneStore.CUSTOMER_EMAIL, "TechZone2026!");
        check(store.cart().containsKey("CPU-14600K"), "Khôi phục giỏ khách mẫu");
        check(store.ordersForCustomer(TechZoneStore.CUSTOMER_ID).stream().noneMatch(o -> o.id.equals(order.order().id)), "Không lẫn đơn khách khác");
        check(store.notifications(Role.CUSTOMER).stream().noneMatch(n -> n.message.contains(order.order().number)), "Không lẫn thông báo khách khác");
        check(store.checkout("x", "Ví mock", "", PaymentVerdict.AUTHORIZED, "order-new-user").order() == null, "Không dùng lại key của khách khác");
    }

    private static void searchAndCompatibility() {
        TechZoneStore store = new TechZoneStore();
        var found = store.search("LGA1700 DDR5", "Mainboard", "LGA1700", "DDR5", 0, true);
        check(!found.isEmpty() && found.stream().allMatch(p -> p.spec("socket").equals("LGA1700") && p.spec("memoryType").equals("DDR5")), "Bộ lọc tìm kiếm");
        var allCategories = store.search("", "Tất cả", "", "", 0, true);
        check(!allCategories.isEmpty() && store.search("", "", "", "", 0, true).size() == allCategories.size(), "Danh mục rỗng hiển thị tất cả sản phẩm đủ điều kiện");
        var filteredAll = store.search("", "", "LGA1700", "DDR5", 0, true);
        check(!filteredAll.isEmpty() && filteredAll.stream().allMatch(p -> p.spec("socket").equals("LGA1700") && p.spec("memoryType").equals("DDR5")), "Danh mục rỗng vẫn giữ bộ lọc kỹ thuật");
        check(store.search("DDR4", "Mainboard", "LGA1700", "DDR5", 0, true).isEmpty(), "Không ghi đè DDR5 bằng DDR4");
        check(store.search("DDR4 DDR5", "Mainboard", "", "", 0, true).isEmpty(), "Mâu thuẫn trong truy vấn");
        check(store.search("", "RAM", "", "", 0, true, Map.of("speed", "600")).isEmpty(), "600 không khớp 6000");
        check(!store.search("", "RAM", "", "", 0, true, Map.of("speed", "6000")).isEmpty(), "6000 khớp 6000 MT/s");
        var cooler = store.search("tản nhiệt cho CPU", "Tất cả", "", "", 0, true);
        check(!cooler.isEmpty() && cooler.stream().allMatch(p -> p.category.equals("Tản nhiệt")), "Hiểu ý định tìm tản nhiệt cho CPU");
        var boardForCpu = store.search("bo mạch chủ cho CPU", "Tất cả", "", "", 0, true);
        check(!boardForCpu.isEmpty() && boardForCpu.stream().allMatch(p -> p.category.equals("Mainboard")), "Hiểu ý định tìm bo mạch cho CPU");
        store.setSearchServiceAvailable(false, "test");
        check(!store.search("LGA1700 DDR5", "Mainboard", "LGA1700", "DDR5", 0, true).isEmpty(), "Fallback tìm kiếm");
        check(store.recommendations("MB-B760-D5", Set.of(), 8).stream().allMatch(p -> store.assessPair("MB-B760-D5",p.id).status()!=vn.techzone.application.PairCompatibility.Status.CONFLICT), "Gợi ý không có xung đột đã biết");
        store.addToCart("PHONE-S24", 1); store.addToCart("CASE-IP16", 1);
        check(!store.validateCart().isBlank(), "Chặn ốp iPhone với Galaxy S24");
        check(!store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.AUTHORIZED).confirmed(), "Không xác nhận giỏ không tương thích");
        String previous = store.product("GPU-4070S").spec("minPsu");
        check(!store.updateTechnicalSpec("GPU-4070S", "minPsu", "unknown", "test").startsWith("Đã cập nhật"), "Chặn công suất không hợp lệ");
        check(store.product("GPU-4070S").spec("minPsu").equals(previous), "Không thay đổi thông số khi sai");
        check(store.updateTechnicalSpec("GPU-4070S", "connector", "PCIe", "test").startsWith("Đã cập nhật"), "Cho thêm trường kỹ thuật");
        store.product("GPU-4070S").specs.put("minPsu", "unknown");
        store.flagCompatibility("GPU-4070S", "PSU-750", "Kiểm tra", Role.SUPPORT, "test");
        CompatibilityReview review = store.compatibilityReviews().stream().filter(r -> r.primaryVariantId.equals("GPU-4070S")
                && r.accessoryVariantId.equals("PSU-750") && r.status.equals("Chờ rà soát")).findFirst().orElseThrow();
        check(store.reviewCompatibility(review.id, true, Role.SUPPORT, "test", "").startsWith("Không thể"), "Dữ liệu công suất lỗi phải bị chặn an toàn");
        TechZoneStore changed = new TechZoneStore();
        changed.product("GPU-4070S").specs.put("minPsu", "unknown");
        check(changed.recommendations("GPU-4070S", Set.of(), 8).stream().allMatch(p -> changed.assessPair("GPU-4070S",p.id).status()==vn.techzone.application.PairCompatibility.Status.UNKNOWN), "Thiếu công suất phải hiển thị chưa xác minh");
        check(changed.assessPair("MB-B760-D5", "SSD-990").status()==vn.techzone.application.PairCompatibility.Status.UNKNOWN, "Khe M.2 chưa chứng minh hỗ trợ NVMe");
        changed.product("MB-B760-D5").specs.put("m2", "Không có M.2");
        check(!changed.compatible("MB-B760-D5", "SSD-990"), "Không coi mô tả không có khe M.2 là tương thích");
        changed.product("MB-B760-D5").specs.put("m2", "0 khe M.2");
        check(!changed.compatible("MB-B760-D5", "SSD-990"), "Không coi 0 khe M.2 là tương thích");
        changed.product("MB-B760-D5").specs.put("m2", "Không có 2 khe M.2");
        check(!changed.compatible("MB-B760-D5", "SSD-990"), "Không bỏ qua phủ định khi kiểm tra khe M.2");
        changed.product("COOLER-PA120").specs.put("socketSupport", "AM50");
        check(!changed.compatible("CPU-7800X3D", "COOLER-PA120"), "Socket AM5 không được khớp một phần với AM50");
        changed.product("CASE-S24").specs.put("forModel", "");
        changed.addToCart("PHONE-S24",1); changed.addToCart("CASE-S24",1);
        check(!changed.validateCart().isBlank() && changed.cartCompatibilityWarning().contains("Chưa được duyệt"), "Thiếu mẫu điện thoại của ốp phải chặn thanh toán");
    }

    private static void paymentAndFulfillment() {
        TechZoneStore store = new TechZoneStore();
        int available = store.available("GPU-4060");
        store.addToCart("GPU-4060", 1);
        var declined = store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.DECLINED, "decline-key");
        check(declined.order().status == OrderStatus.CANCELLED && store.available("GPU-4060") == available, "Từ chối trả tồn");
        var unknown = store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.UNKNOWN, "unknown-key");
        check(unknown.order().status == OrderStatus.PENDING_REVIEW && store.available("GPU-4060") == available - 1, "Chưa rõ giữ tồn");
        check(!store.verifySalesOrder(unknown.order().id, Role.SALES, "test").startsWith("Đã xác minh"), "Không xác minh bán hàng khi thanh toán chưa rõ");
        check(unknown.order().reconciliationDueAt != null, "Có hạn nhắc đối soát");
        unknown.order().reconciliationDueAt = java.time.LocalDateTime.now().minusMinutes(1);
        check(store.escalateOverduePaymentReviews() == 1 && store.escalateOverduePaymentReviews() == 0,
                "Quá hạn chỉ phát cảnh báo một lần");
        check(unknown.order().status == OrderStatus.PENDING_REVIEW && store.available("GPU-4060") == available - 1,
                "Quá hạn không tự trả tồn khi kết quả chưa rõ");
        check(store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.UNKNOWN, "unknown-key").order() == unknown.order(), "Idempotency trả cùng đơn");
        store.reconcilePaymentCallback(unknown.order().id, "callback-1", PaymentVerdict.AUTHORIZED, "");
        check(unknown.order().status == OrderStatus.PENDING_REVIEW, "Không duyệt nếu thiếu token");
        store.reconcilePaymentCallback(unknown.order().id, "callback-1", PaymentVerdict.AUTHORIZED, "UNVERIFIED");
        check(unknown.order().status == OrderStatus.PENDING_REVIEW, "Không duyệt token mock không hợp lệ");
        store.reconcilePaymentCallback(unknown.order().id, "callback-1", PaymentVerdict.AUTHORIZED, "MOCK-TOKEN-1");
        check(unknown.order().status == OrderStatus.CONFIRMED && unknown.order().paymentReference.equals("MOCK-TOKEN-1"), "Duyệt sau đối soát và ghi token");
        check(store.reconcilePaymentCallback(unknown.order().id, "callback-1", PaymentVerdict.AUTHORIZED, "MOCK-TOKEN-1").contains("đã được xử lý"), "Callback trùng không xử lý lại");

        SalesOrder pick = store.order("ORD-1042");
        check(!store.verifySalesOrder(pick.id, Role.CUSTOMER, "test").startsWith("Đã xác minh"), "Khách không xác minh nghiệp vụ bán hàng");
        check(store.verifySalesOrder(pick.id, Role.SALES, "test").startsWith("Đã xác minh") && pick.salesVerifiedAt != null,
                "Nhân viên bán hàng xác minh đơn đã thanh toán");
        OrderLine cpu = pick.lines.get(0);
        String wrong = store.sampleReservedSerial(pick.lines.get(1).variantId, pick.id);
        store.allocateSerial(pick.id, cpu.variantId, wrong, "test");
        check(cpu.allocatedQuantity == 0, "Từ chối Serial sai SKU");
        for (OrderLine line : pick.lines) {
            int before = store.available(line.variantId);
            String serial = store.sampleReservedSerial(line.variantId, pick.id);
            store.allocateSerial(pick.id, line.variantId, serial, "test");
            store.allocateSerial(pick.id, line.variantId, serial, "test");
            check(line.allocatedQuantity == 1 && store.available(line.variantId) == before, "Cấp Serial một lần, không trừ available hai lần");
        }
        String serial = pick.lines.get(0).serials.get(0);
        check(store.warranty(serial) == null, "Không kích hoạt bảo hành trước giao");
        store.shipOrder(pick.id, "Hãng mock", 0, "40 x 30 x 20 cm", "test");
        check(pick.status == OrderStatus.CONFIRMED, "Không tạo vận đơn khi trọng lượng không hợp lệ");
        store.shipOrder(pick.id, "Hãng mock", 2500, "40 x 30 x 20 cm", "test");
        check(pick.status == OrderStatus.SHIPPED && pick.packageWeightGrams == 2500
                && pick.packageDimensions.equals("40 x 30 x 20 cm"), "Lưu thông tin kiện hàng cho bản kê");
        store.recordShipmentMilestone(pick.id, "transit-1", "Đang trung chuyển", "Kho", "test");
        store.recordShipmentMilestone(pick.id, "out-1", "Đang giao tới bạn", "HCM", "test");
        store.recordShipmentMilestone(pick.id, "delivered-1", "Đã giao thành công", "HCM", "test");
        Warranty activated = store.warranty(serial);
        int count = store.warranties().size();
        store.recordShipmentMilestone(pick.id, "delivered-1", "Đã giao thành công", "HCM", "test");
        check(activated != null && store.warranties().size() == count && pick.status == OrderStatus.DELIVERED, "Giao hàng kích hoạt đúng một lần");
        check(store.sessionTurnover() > 0, "Vòng quay theo phiên thay đổi sau giao");
    }

    private static void providerBoundaries() {
        PaymentPort unavailable = new PaymentPort() {
            @Override public Authorization authorize(String orderId, long amount, String key, PaymentVerdict outcome) { throw new IllegalStateException("timeout"); }
            @Override public boolean verifyCallback(String orderId, String eventId, PaymentVerdict verdict, String token) { return false; }
        };
        TechZoneStore store = new TechZoneStore(unavailable, new MockDeliveryGateway());
        int before = store.available("GPU-4060");
        store.addToCart("GPU-4060", 1);
        var timeout = store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.AUTHORIZED, "provider-timeout");
        check(timeout.order().status == OrderStatus.PENDING_REVIEW && store.available("GPU-4060") == before - 1,
                "Lỗi gateway giữ tồn và chờ đối soát");
        store.reconcilePaymentCallback(timeout.order().id, "unverified-callback", PaymentVerdict.AUTHORIZED, "MOCK-TOKEN");
        check(timeout.order().status == OrderStatus.PENDING_REVIEW, "Không duyệt callback gateway từ chối xác minh");

        DeliveryPort rejectDelivery = new DeliveryPort() {
            @Override public String createTrackingCode(DispatchRequest request) { return "MOCK-TRACK"; }
            @Override public boolean verifyCallback(String trackingCode, String callbackId, String milestone) { return false; }
        };
        TechZoneStore delivery = new TechZoneStore(new MockPaymentGateway(), rejectDelivery);
        SalesOrder shipped = delivery.order("ORD-1038");
        String serial = shipped.lines.get(0).serials.get(0);
        delivery.recordShipmentMilestone(shipped.id, "bad-delivery", "Đã giao thành công", "HCM", "test");
        check(shipped.status == OrderStatus.SHIPPED && delivery.warranty(serial) == null,
                "Không kích hoạt bảo hành khi callback vận chuyển không được xác minh");
    }

    private static void warrantyAndReporting() {
        TechZoneStore store = new TechZoneStore();
        check(store.warranty("SN-TZ-PHONEIP16-0001").status == WarrantyStatus.EXPIRED, "Mẫu bảo hành hết hạn");
        check(store.requestPaidRepair("SN-TZ-PHONEIP16-0001", TechZoneStore.CUSTOMER_ID, "Lỗi pin").startsWith("Đã tạo"), "Yêu cầu báo giá ngoài bảo hành");
        check(store.rmas().stream().anyMatch(r -> r.type.equals("Sửa chữa tính phí") && r.status.equals("Chờ báo giá")), "Phiếu sửa chữa có loại và trạng thái");
        check(store.requestManualWarrantyVerification("UNKNOWN-SERIAL", "Không khởi động").startsWith("Đã gửi"), "Gửi xác minh thủ công");
        WarrantyVerification missing = store.warrantyVerifications().get(0);
        store.reviewWarrantyVerification(missing.id, Role.SUPPORT, "test");
        check(missing.status.equals("Từ chối"), "Từ chối khi không đối chiếu được đơn");
        try {
            var field = TechZoneStore.class.getDeclaredField("warranties");
            field.setAccessible(true);
            ((Map<?, ?>) field.get(store)).remove("SN-TZ-PHONES24-0001");
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        check(store.warranty("SN-TZ-PHONES24-0001") == null, "Giả lập thiếu chứng nhận bảo hành");
        store.requestManualWarrantyVerification("SN-TZ-PHONES24-0001", "Lỗi màn hình");
        WarrantyVerification valid = store.warrantyVerifications().get(1);
        check(store.reviewWarrantyVerification(valid.id, Role.CUSTOMER, "test").startsWith("Chỉ"), "Khách không được tự duyệt");
        store.reviewWarrantyVerification(valid.id, Role.SUPPORT, "test");
        check(valid.status.equals("Đã xác minh · tạo RMA") && store.warranty("SN-TZ-PHONES24-0001") != null,
                "Xác minh hồ sơ đã giao khôi phục bảo hành và tạo RMA");
        long expected = store.paidOrdersThisMonth().stream().mapToLong(o -> o.subtotal - o.discount).sum();
        check(store.revenueThisMonth() == expected, "Doanh thu tính từ đơn thực tế không cộng số cố định");
        long before = store.revenueThisMonth();
        store.addToCart("GPU-4060", 1);
        var order = store.checkout("Địa chỉ", "Ví mock", "TECHZONE5", PaymentVerdict.AUTHORIZED);
        check(store.revenueThisMonth() == before + order.order().subtotal - order.order().discount, "Doanh thu cập nhật sau đơn duyệt");
        var paidAt = order.order().paidAt;
        order.order().paidAt = paidAt.minusMonths(1);
        check(store.revenueThisMonth() == before, "Doanh thu theo thời điểm xác nhận thanh toán");
        order.order().paidAt = paidAt;
        int qty = store.available("PSU-750");
        store.createRestockRequest("PSU-750", 2, "test");
        check(store.available("PSU-750") == qty, "PO chưa nhận không tăng tồn");
        store.receiveRestockRequest(store.restockRequests().get(0).id, "test");
        check(store.available("PSU-750") == qty + 2, "Nhận PO cập nhật tồn");

        TechZoneStore serials = new TechZoneStore();
        serials.addToCart("CPU-14700K", serials.available("CPU-14700K") - 1);
        serials.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.UNKNOWN);
        String removed = serials.sampleAvailableSerial("CPU-14700K");
        serials.adjustInventory("CPU-14700K", -1, "Thiết bị hỏng", "test");
        serials.restock("CPU-14700K", 1, "test");
        check(!serials.sampleAvailableSerial("CPU-14700K").equals(removed), "Không tái sử dụng Serial đã điều chỉnh khỏi kho");
    }

    private static void consentAndRecommendationMetrics() {
        TechZoneStore store = new TechZoneStore();
        store.setInteractionConsent(true);
        store.recordSearch("DDR5", "memory=DDR5", store.search("DDR5", "RAM", "", "DDR5", 0, true), "mock", 1);
        store.recordSearch("DDR4", "memory=DDR4", store.search("DDR4", "RAM", "", "DDR4", 0, true), "mock", 1);
        check(store.aiMetrics().filterChanges() == 1, "Ghi thay đổi bộ lọc có đồng ý");
        store.addRecommendedToCart("PHONE-S24", "CASE-S24", 1);
        check(store.aiMetrics().recommendationCartAdds() == 1, "Đếm thêm giỏ từ gợi ý");
        store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.UNKNOWN, "rec-unknown");
        SalesOrder order = store.orders().stream().filter(o -> o.idempotencyKey.equals("rec-unknown")).findFirst().orElseThrow();
        check(store.aiMetrics().recommendationPurchases() == 0, "Chưa tính mua khi chưa rõ thanh toán");
        store.registerCustomer("Khách khác", "other@example.com", "Password123!".toCharArray());
        login(store, "other@example.com", "Password123!");
        check(!store.interactionConsent(), "Đồng ý ghi sự kiện tách theo khách");
        store.reconcilePaymentCallback(order.id, "callback-rec", PaymentVerdict.AUTHORIZED, "MOCK-OK");
        check(store.aiMetrics().recommendationPurchases() == 1, "Đếm mua từ gợi ý sau đối soát cho đúng khách");
        store.setInteractionConsent(false);
        check(store.aiMetrics().recommendationPurchases() == 1, "Tắt đồng ý khách khác không xóa sự kiện chủ đơn");
        login(store, TechZoneStore.CUSTOMER_EMAIL, "TechZone2026!");
        store.setInteractionConsent(false);
        check(store.aiMetrics().queries() == 0 && store.aiMetrics().recommendationPurchases() == 0, "Thu hồi đồng ý xóa sự kiện của khách hiện tại");
    }

    private static void localAiLearning() {
        LocalAiEngine engine = new LocalAiEngine();
        ProductVariant board = new ProductVariant("A", "A", "Bo mạch chủ", "Mainboard", "Demo", "LGA1700 DDR5", "#000000", 1, 0, 12, Map.of("socket", "LGA1700"));
        ProductVariant ram = new ProductVariant("B", "B", "Bộ nhớ DDR5", "RAM", "Demo", "6000 MT/s", "#000000", 1, 0, 12, Map.of("memoryType", "DDR5"));
        ProductVariant disk = new ProductVariant("C", "C", "Ổ cứng NVMe", "SSD", "Demo", "Lưu trữ", "#000000", 1, 0, 12, Map.of("capacity", "1 TB"));
        List<List<String>> baskets = new ArrayList<>();
        for (int i = 0; i < 8; i++) baskets.add(List.of("A", "B"));
        baskets.add(List.of("A", "C"));
        engine.fit(List.of(board, ram, disk), baskets);
        var scores = engine.rankSearch("motherboard", List.of(board, ram, disk));
        check(scores.get("A") > scores.get("B"), "TF-IDF nhận diện tên danh mục qua từ đồng nghĩa");
        double ab = engine.recommendationScore("A", "B", Set.of());
        double ac = engine.recommendationScore("A", "C", Set.of());
        check(ab > ac, "Mô hình học đồng xuất hiện từ giỏ mẫu");
        baskets.clear();
        for (int i = 0; i < 8; i++) baskets.add(List.of("A", "C"));
        baskets.add(List.of("A", "B"));
        engine.fit(List.of(board, ram, disk), baskets);
        check(engine.recommendationScore("A", "C", Set.of()) > engine.recommendationScore("A", "B", Set.of()), "Thay dữ liệu huấn luyện thay thứ hạng");

        TechZoneStore store = new TechZoneStore();
        int sampleBaskets = store.aiTrainingBasketCount();
        store.setInteractionConsent(true);
        int permittedBefore = store.aiTrainingBasketCount();
        store.addToCart("CPU-14700K", 1); store.addToCart("MB-B760-D5", 1);
        check(store.checkout("Địa chỉ", "Ví mock", "", PaymentVerdict.AUTHORIZED).confirmed(), "Tạo giỏ để huấn luyện có đồng ý");
        check(store.aiTrainingBasketCount() == permittedBefore + 1, "Đơn có đồng ý cập nhật mô hình");
        store.setInteractionConsent(false);
        check(store.aiTrainingBasketCount() == sampleBaskets, "Thu hồi đồng ý loại dữ liệu khách khỏi mô hình");
    }

    private static void recommendationAlignment() throws Exception {
        TechZoneStore store = new TechZoneStore();
        try {
            var cold = store.recommend("CPU-14600K", Set.of(), 3);
            check(cold.strategy().contains("Fallback phổ biến") && !cold.items().isEmpty(), "Khách mới dùng gợi ý phổ biến");
            check(cold.items().get(0).id.equals("MB-B760-D5"), "Fallback xếp theo số giỏ mẫu, không theo tồn kho");
            store.balance("MB-B760-D4").available = 100;
            store.setRecommendationServiceAvailable(false, "test");
            var unavailable = store.recommend("CPU-14600K", Set.of(), 3);
            check(unavailable.strategy().contains("dịch vụ không sẵn sàng") && unavailable.items().get(0).id.equals("MB-B760-D5"), "Lỗi dịch vụ vẫn theo độ phổ biến");
            check(unavailable.items().stream().allMatch(p -> store.assessPair("CPU-14600K",p.id).status()!=vn.techzone.application.PairCompatibility.Status.CONFLICT), "Fallback vẫn lọc xung đột");
            store.setRecommendationServiceAvailable(true, "test");
            var mixed = store.recommend("CPU-14600K", Set.of("CPU-7800X3D"), 8);
            check(mixed.items().stream().noneMatch(p -> p.id.equals("MB-B760-D5") || p.id.equals("MB-B760-D4")), "Không gợi ý mainboard LGA1700 khi giỏ có CPU AM5");
            store.cart().clear(); store.addToCart("CPU-7800X3D", 1);
            check(!store.addRecommendedToCart("CPU-14600K", "MB-B760-D5", 1).startsWith("Đã thêm"), "Kiểm tra lại toàn bộ giỏ trước khi thêm gợi ý");
            store.cart().clear();
            check(store.flagCompatibility("GPU-4070S", "PSU-750", "kiểm tra", Role.CUSTOMER, "test").startsWith("Vai trò"), "Khách không được gắn cờ với quyền nhân viên");
            check(store.compatible("GPU-4070S", "PSU-750"), "Từ chối quyền không làm thay đổi cặp");
            check(store.reviewCompatibility("CMP-014", false, Role.SALES, "test", "").startsWith("Vai trò"), "Nhân viên bán hàng không được duyệt cặp");
            check(!store.compatible("MON-27", "MOUSE-G502"), "Không quảng cáo tương thích khi thiếu quy tắc kỹ thuật");

            store.setInteractionConsent(true);
            store.recordProductView("GPU-4060");
            var ranked = store.recommend("CPU-14600K", Set.of("CPU-14600K", "GPU-4060"), 3);
            check(ranked.strategy().startsWith("AI") && ranked.inferenceMs() > 0, "Có ngữ cảnh thì dùng mô hình đồng xuất hiện");
            var hybrid = store.recommendWithEmbeddings("CPU-14600K", Set.of("CPU-14600K", "GPU-4060"), 8,
                    Map.of("MB-B650", 1.0, "MB-B760-D5", 0.8), "BGE-M3");
            check(hybrid.strategy().contains("BGE-M3"), "Gợi ý nhận tín hiệu ngữ nghĩa pretrained");
            check(hybrid.items().stream().noneMatch(p -> p.id.equals("MB-B650"))
                    && hybrid.items().stream().allMatch(p -> store.assessPair("CPU-14600K",p.id).status()!=vn.techzone.application.PairCompatibility.Status.CONFLICT && store.available(p.id)>0),
                    "Điểm ngữ nghĩa không vượt bộ lọc tương thích và tồn kho");
            store.recordRecommendationResult("CPU-14600K", ranked);
            check(store.interactionEvents().stream().anyMatch(e -> e.type.equals("recommendation_request") && e.detail.contains("inferenceMs=")), "Ghi thời gian suy luận và tổng thời gian khi được đồng ý");
            store.recordRecommendationClick("CPU-14600K", "MB-B760-D5");
            long clicks = store.aiMetrics().recommendationClicks();
            store.addRecommendedToCart("CPU-14600K", "MB-B760-D5", 1);
            check(store.aiMetrics().recommendationClicks() == clicks && store.aiMetrics().recommendationCartAdds() > 0, "Lượt xem và thêm giỏ là hai sự kiện độc lập");

            java.lang.reflect.Field executorField = TechZoneStore.class.getDeclaredField("recommendationExecutor"); executorField.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) executorField.get(store);
            java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
            executor.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            check(occupied.await(2, java.util.concurrent.TimeUnit.SECONDS), "Tạo tác vụ suy luận chậm");
            try {
                var timedOut = store.recommend("CPU-14600K", Set.of("CPU-14600K", "GPU-4060"), 3);
                check(timedOut.strategy().contains("quá thời gian") && timedOut.items().stream().allMatch(p -> store.assessPair("CPU-14600K",p.id).status()!=vn.techzone.application.PairCompatibility.Status.CONFLICT), "Quá thời gian phải loại xung đột");
            } finally { release.countDown(); }

            var evaluation = store.runRecommendationEvaluation();
            check(evaluation.trainBaskets() == 14 && evaluation.testBaskets() == 4 && evaluation.cases() > 0, "Đánh giá chia giỏ mẫu trước/sau không dùng giỏ kiểm tra để huấn luyện");
            check(evaluation.recallAtK() >= 0 && evaluation.recallAtK() <= 1 && evaluation.ndcgAtK() >= 0 && evaluation.ndcgAtK() <= 1, "Recall/NDCG gợi ý hợp lệ");
            check(evaluation.violations() == 0 && evaluation.checkedItems() > 0, "Đánh giá không trả cặp không tương thích");
            check(evaluation.latencySamples() + evaluation.fallbackSamples() == 30 && evaluation.p95TotalMs() > 0, "Ghi số mẫu và p95 độ trễ");
            executor.shutdownNow();
            var failed = store.recommend("CPU-14600K", Set.of("CPU-14600K", "GPU-4060"), 3);
            check(failed.strategy().contains("lỗi mô hình") && failed.items().stream().allMatch(p -> store.assessPair("CPU-14600K",p.id).status()!=vn.techzone.application.PairCompatibility.Status.CONFLICT), "Lỗi mô hình phải loại xung đột");
        } finally { store.closeRepository(); }
    }

    private static void persistence() throws Exception {
        Path directory = Files.createTempDirectory("techzone-regression-");
        Path path = directory.resolve("state.dat");
        try {
            String id;
            int stockAfter;
            try (DemoStoreRepository repo = DemoStoreRepository.open(path)) {
                TechZoneStore store = repo.loadOrCreate();
                store.attachRepository(repo);
                store.setRecommendationLimit(5, "Kiểm thử");
                String legacySerial = store.sampleAvailableSerial("CPU-14600K");
                var legacyUnit = store.unit(legacySerial);
                legacyUnit.status = UnitStatus.RETURN_PENDING;
                store.balance("CPU-14600K").available--;
                var legacyField = TechZoneStore.class.getDeclaredField("supplierReturns");
                legacyField.setAccessible(true);
                @SuppressWarnings("unchecked") List<SupplierReturn> legacyReturns = (List<SupplierReturn>) legacyField.get(store);
                legacyReturns.add(new SupplierReturn("RTV-OLD", "CPU-14600K", legacySerial, "Đã tồn tại", "NCC", "Kho"));
                var oldPromotion = TechZoneStore.class.getDeclaredField("promotionPercent"); oldPromotion.setAccessible(true); oldPromotion.setInt(store, 10);
                store.product("CPU-14600K").price = 7_000_000; store.product("CPU-14600K").salePrice = 6_000_000;
                store.registerCustomer("Khách lưu", "saved@example.com", "Password123!".toCharArray());
                login(store, "saved@example.com", "Password123!");
                store.addToCart("GPU-4060", 1);
                var order = store.checkout("Địa chỉ đã lưu", "Ví mock", "", PaymentVerdict.UNKNOWN, "persist-key");
                id = order.order().id;
                stockAfter = store.available("GPU-4060");
                check(store.flushData(), "Lưu dữ liệu vào tệp cục bộ");
                boolean locked = false;
                try (DemoStoreRepository unexpected = DemoStoreRepository.open(path)) {
                    throw new AssertionError("A locked repository unexpectedly opened: " + unexpected.dataFile());
                }
                catch (java.io.IOException ex) { locked = true; }
                check(locked, "Không cho hai cửa sổ cùng ghi một tệp");
            }
            try (DemoStoreRepository repo = DemoStoreRepository.open(path)) {
                TechZoneStore loaded = repo.loadOrCreate(); loaded.attachRepository(repo);
                check(loaded.promotionPercent() == 10 && loaded.product("CPU-14600K").effectivePrice() == 6_000_000, "Giữ giá và ưu đãi từ dữ liệu phiên bản trước");
                SupplierReturn oldReturn = loaded.supplierReturns().get(0);
                int priorStock = loaded.available("CPU-14600K");
                check(loaded.unit(oldReturn.serial).status == UnitStatus.RETURN_PENDING, "Giữ Serial đang chờ trả từ dữ liệu cũ");
                check(loaded.cancelSupplierReturn(oldReturn.id, Role.WAREHOUSE, "Kiểm thử").startsWith("Đã hủy")
                        && loaded.unit(oldReturn.serial).status == UnitStatus.AVAILABLE && loaded.available("CPU-14600K") == priorStock + 1,
                        "Có thể xử lý yêu cầu cũ và khôi phục Serial");
                check(loaded.recommendationLimit() == 5, "Cấu hình số gợi ý được lưu qua khởi động lại");
                boolean invalidLimit = false;
                try { loaded.setRecommendationLimit(9, "Kiểm thử"); } catch (IllegalArgumentException ex) { invalidLimit = true; }
                check(invalidLimit && loaded.recommendationLimit() == 5, "Chặn số gợi ý ngoài giới hạn");
                login(loaded, "saved@example.com", "Password123!");
                check(loaded.order(id) != null && loaded.order(id).customerId.equals(loaded.signedInCustomerId()), "Khôi phục tài khoản và đơn đúng chủ");
                check(loaded.order(id).status == OrderStatus.PENDING_REVIEW && loaded.available("GPU-4060") == stockAfter, "Khôi phục reservation chờ đối soát");
                check(loaded.checkout("Địa chỉ đã lưu", "Ví mock", "", PaymentVerdict.UNKNOWN, "persist-key").order().id.equals(id), "Idempotency được giữ qua khởi động lại");
                check(loaded.aiVocabularySize() > 0 && !loaded.search("LGA1700 DDR5", "Mainboard", "LGA1700", "DDR5", 0, true).isEmpty(), "Khôi phục mô hình AI sau khi mở dữ liệu");
            }
            byte[] invalid = new byte[]{1, 2, 3, 4}; Files.write(path, invalid);
            try (DemoStoreRepository repo = DemoStoreRepository.open(path)) {
                boolean rejected = false;
                try { repo.loadOrCreate(); } catch (java.io.IOException ex) { rejected = true; }
                check(rejected && java.util.Arrays.equals(Files.readAllBytes(path), invalid), "Tệp hỏng không bị ghi đè bằng dữ liệu mẫu");
            }
        } finally {
            try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
            try { Files.deleteIfExists(directory.resolve("state.dat.lock")); } catch (java.io.IOException ignored) { }
            try { Files.deleteIfExists(directory); } catch (java.io.IOException ignored) { }
        }
    }

    public static void main(String[] args) throws Exception {
        accountIsolation(); searchAndCompatibility(); paymentAndFulfillment(); providerBoundaries(); warrantyAndReporting(); consentAndRecommendationMetrics(); localAiLearning(); recommendationAlignment(); persistence();
        System.out.println("TechZone regression checks passed: " + checks);
    }
}
