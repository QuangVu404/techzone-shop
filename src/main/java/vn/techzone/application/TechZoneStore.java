package vn.techzone.application;

import vn.techzone.domain.TechZoneModels;
import vn.techzone.domain.TechZoneModels.*;
import vn.techzone.domain.Payment;
import vn.techzone.application.port.PaymentPort;
import vn.techzone.application.port.DeliveryPort;
import vn.techzone.infrastructure.MockPaymentGateway;
import vn.techzone.infrastructure.MockDeliveryGateway;
import vn.techzone.infrastructure.LocalAiEngine;
import vn.techzone.infrastructure.StoreRepository;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stateful demo session and relational persistence model. Workflow policy lives in application services,
 * while this class coordinates mutable demo state and supplies state operations to the infrastructure adapter.
 * Stock counters follow available -> reserved -> allocated; a scan never deducts available twice.
 */
public final class TechZoneStore implements Serializable, vn.techzone.application.port.CheckoutWorkflowPort {
    private static final long serialVersionUID = 1L;
    public record GearVNCatalogProduct(String id,String name,String category,String brand,String description,
                                       long price,long salePrice,String sourceSku,String availability,
                                       String sourceUrl,String imageUrl,LocalDateTime fetchedAt,
                                       Map<String,String> specs) {
        public GearVNCatalogProduct { specs=Map.copyOf(specs); }
    }
    private static final ThreadLocal<Boolean> PRESERVE_SESSION = ThreadLocal.withInitial(() -> false);
    /** Internal trusted transport can preserve the current session; file startup resets it. */
    public static void preserveDeserializedSession(boolean preserve) {
        if (preserve) PRESERVE_SESSION.set(true); else PRESERVE_SESSION.remove();
    }
    public static final String CUSTOMER_ID = "CUS-001";
    public static final String CUSTOMER_NAME = "Nguyễn Minh Linh";
    public static final String CUSTOMER_EMAIL = "linh.nguyen@example.com";
    public static final String CUSTOMER_ADDRESS = "42 Nguyễn Huệ, P. Bến Nghé, Q. 1, TP. Hồ Chí Minh";

    private final Map<String, ProductVariant> products = new LinkedHashMap<>();
    private transient CatalogSearchService catalogSearchService = new CatalogSearchService();
    private transient RecommendationRankingService recommendationRankingService = new RecommendationRankingService();
    private transient RecommendationEligibilityService recommendationEligibilityService = new RecommendationEligibilityService();
    private transient CheckoutWorkflowService checkoutWorkflowService = new CheckoutWorkflowService();
    private transient PaymentPort paymentGateway;
    private transient DeliveryPort deliveryGateway;
    private transient LocalAiEngine aiEngine = new LocalAiEngine();
    private transient Map<String, Integer> recommendationPopularity = Map.of();
    private transient java.util.concurrent.ThreadPoolExecutor recommendationExecutor = newRecommendationExecutor();
    private transient StoreRepository repository;
    private transient boolean dirty = true;
    private transient String persistenceError = "";
    private final Map<String, AccountRecord> accounts = new LinkedHashMap<>();
    private final Map<String, InventoryBalance> balances = new LinkedHashMap<>();
    private final Map<String, InventoryUnit> unitsBySerial = new LinkedHashMap<>();
    private final Set<String> issuedSerials = new LinkedHashSet<>();
    private final Map<String, Map<String, CartItem>> carts = new LinkedHashMap<>();
    private Map<String, CartItem> cart = new LinkedHashMap<>();
    private final Map<String, SalesOrder> orders = new LinkedHashMap<>();
    private final Map<String, String> orderByIdempotencyKey = new LinkedHashMap<>();
    private final Set<String> paymentCallbackIds = new LinkedHashSet<>();
    private final Set<String> escalatedPaymentOrders = new LinkedHashSet<>();
    private final Map<String, Warranty> warranties = new LinkedHashMap<>();
    private final Map<String, RmaCase> rmas = new LinkedHashMap<>();
    private final Map<String, WarrantyVerification> warrantyVerifications = new LinkedHashMap<>();
    private final Map<String, List<ShipmentEvent>> shipmentEvents = new LinkedHashMap<>();
    private final Map<String, Role> demoRoleAssignments = new LinkedHashMap<>();
    private final Set<String> approvedPairs = new LinkedHashSet<>();
    private final List<InteractionEvent> interactionEvents = new ArrayList<>();
    private List<RecommendationImpression> recommendationImpressions = new ArrayList<>();
    private final Map<String, String> lastSearchFilters = new HashMap<>();
    private final Map<String, Boolean> consentByCustomer = new HashMap<>();
    private final Map<String, Set<String>> recommendedCartItems = new HashMap<>();
    private final Map<String, Set<String>> recommendedOrderItems = new HashMap<>();
    private final List<AuditEntry> auditEntries = new ArrayList<>();
    private final List<RestockRequest> restockRequests = new ArrayList<>();
    // Kept to read older demo files and resolve returns that were already pending.
    private List<SupplierReturn> supplierReturns = new ArrayList<>();
    private final List<CompatibilityReview> compatibilityReviews = new ArrayList<>();
    private final List<AppNotification> notifications = new ArrayList<>();
    private final List<ConsultationNote> consultationNotes = new ArrayList<>();
    private final List<String[]> reviews = new ArrayList<>();
    private final Set<String> deliveryEvents = new LinkedHashSet<>();
    private final Set<String> shipmentCallbackIds = new LinkedHashSet<>();
    private final Set<String> lowStockNotified = new LinkedHashSet<>();
    private final Map<String, Integer> supplierLeadDays = new LinkedHashMap<>();
    private transient Map<String, Integer> openingSellable = new LinkedHashMap<>();
    private transient LocalDateTime sessionStartedAt = LocalDateTime.now();
    private boolean interactionConsent;
    private boolean searchServiceAvailable = true;
    private boolean recommendationServiceAvailable = true;
    // Zero is treated as the default when reading data saved before this setting existed.
    private int recommendationLimit;
    // Retain serialized settings from prior versions rather than overwriting user data.
    private long monthlySalesTarget;
    private int promotionPercent;
    private int catalogRevision = 1;
    private int searchIndexRevision = 1;
    private int recommendationIndexRevision = 1;
    private final String rankingModelVersion = "tfidf-cosine-itemcf-v1";
    private long orderSequence = 1043;
    private int rmaSequence = 207;
    private int verificationSequence = 1;
    private int notificationSequence = 1;
    private int noteSequence = 1;
    private int customerSequence = 2;
    private String signedInEmail = "";
    private String signedInName = "";
    private String signedInCustomerId = "";

    public TechZoneStore() {
        this(new MockPaymentGateway(), new MockDeliveryGateway());
    }

    public TechZoneStore(PaymentPort paymentGateway, DeliveryPort deliveryGateway) {
        this.paymentGateway = Objects.requireNonNull(paymentGateway);
        this.deliveryGateway = Objects.requireNonNull(deliveryGateway);
        seedProducts();
        seedAccounts();
        seedCompatibility();
        seedOrders();
        for (SalesOrder order : orders.values()) orderByIdempotencyKey.put(order.idempotencyKey, order.id);
        for (ProductVariant product : products.values()) {
            InventoryBalance b = balance(product.id);
            openingSellable.put(product.id, b.available + b.reserved);
        }
        seedReviews();
        seedRoleAssignments();
        seedShipmentHistory();
        evaluateLowStockNotifications();
        rebuildAiModel();
        compatibilityReviews.add(new CompatibilityReview("CMP-014", "GPU-4070S", "PSU-550",
                "Cặp linh kiện được báo cáo có thể không đạt công suất tối thiểu 650 W.", LocalDateTime.now().minusHours(3)));
        seedVerifiedStorageFacts();
    }

    private void seedRoleAssignments() {
        demoRoleAssignments.put("demo.admin@techzone.local", Role.ADMIN);
        demoRoleAssignments.put("demo.manager@techzone.local", Role.MANAGER);
        demoRoleAssignments.put("demo.sales@techzone.local", Role.SALES);
        demoRoleAssignments.put("demo.warehouse@techzone.local", Role.WAREHOUSE);
        demoRoleAssignments.put("demo.support@techzone.local", Role.SUPPORT);
        demoRoleAssignments.put("linh.nguyen@example.com", Role.CUSTOMER);
    }

    private void seedShipmentHistory() {
        SalesOrder shipped = orders.get("ORD-1038");
        if (shipped != null) {
            putShipmentEvent("SEED-1038-PICKUP", shipped, "Đã lấy hàng", "TP. Hồ Chí Minh", "Đơn vị vận chuyển đã nhận kiện hàng.", LocalDateTime.now().minusDays(2));
            putShipmentEvent("SEED-1038-TRANSIT", shipped, "Đang trung chuyển", "Trung tâm khai thác Bình Dương", "Kiện hàng đang trên đường tới bưu cục nhận.", LocalDateTime.now().minusDays(1));
            putShipmentEvent("SEED-1038-OUT", shipped, "Đang giao tới bạn", "Q. 1, TP. Hồ Chí Minh", "Shipper đang giao kiện hàng.", LocalDateTime.now().minusHours(4));
        }
        SalesOrder delivered = orders.get("ORD-0897");
        if (delivered != null) {
            putShipmentEvent("SEED-0897-PICKUP", delivered, "Đã lấy hàng", "TP. Hồ Chí Minh", "Đơn vị vận chuyển đã nhận kiện hàng.", delivered.deliveredAt.minusDays(2));
            putShipmentEvent("SEED-0897-TRANSIT", delivered, "Đang trung chuyển", "Đà Nẵng", "Kiện hàng đã tới trung tâm khai thác.", delivered.deliveredAt.minusDays(1));
            putShipmentEvent("SEED-0897-OUT", delivered, "Đang giao tới bạn", "Q. Hải Châu, Đà Nẵng", "Shipper đang giao kiện hàng.", delivered.deliveredAt.minusHours(3));
            putShipmentEvent("SEED-0897-DELIVERED", delivered, "Đã giao thành công", "Q. Hải Châu, Đà Nẵng", "Người nhận đã xác nhận nhận hàng.", delivered.deliveredAt);
        }
    }

    private static final class AccountRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        final String name;
        final String email;
        final byte[] salt;
        final byte[] passwordHash;
        String phoneNumber;
        String address;
        java.time.LocalDateTime createdAt;
        java.time.LocalDateTime updatedAt;
        AccountRecord(String id, String name, String email, String address, byte[] salt, byte[] passwordHash) {
            this(id,name,email,"",address,salt,passwordHash);
        }
        AccountRecord(String id,String name,String email,String phoneNumber,String address,byte[] salt,byte[] passwordHash) {
            this(id,name,email,phoneNumber,address,salt,passwordHash,java.time.LocalDateTime.now(),java.time.LocalDateTime.now());
        }
        AccountRecord(String id,String name,String email,String phoneNumber,String address,byte[] salt,byte[] passwordHash,
                      java.time.LocalDateTime createdAt,java.time.LocalDateTime updatedAt) {
            this.id=id;this.name=name;this.email=email;this.phoneNumber=phoneNumber==null?"":phoneNumber;
            this.address=address;this.salt=salt;this.passwordHash=passwordHash;this.createdAt=createdAt;this.updatedAt=updatedAt;
        }
    }

    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (supplierReturns == null) supplierReturns = new ArrayList<>();
        if (recommendationImpressions == null) recommendationImpressions = new ArrayList<>();
        paymentGateway = new MockPaymentGateway(); deliveryGateway = new MockDeliveryGateway(); aiEngine = new LocalAiEngine();
        catalogSearchService = new CatalogSearchService();
        recommendationRankingService = new RecommendationRankingService();
        recommendationEligibilityService = new RecommendationEligibilityService();
        checkoutWorkflowService = new CheckoutWorkflowService();
        recommendationExecutor = newRecommendationExecutor();
        openingSellable = new LinkedHashMap<>(); sessionStartedAt = LocalDateTime.now();
        for (ProductVariant product : products.values()) {
            InventoryBalance b = balance(product.id);
            if (b == null) throw new InvalidObjectException("Thiếu tồn kho cho " + product.id);
            openingSellable.put(product.id, b.available + b.reserved);
        }
        AccountRecord sample = accounts.get(CUSTOMER_EMAIL.toLowerCase(Locale.ROOT));
        if (sample == null) throw new InvalidObjectException("Thiếu tài khoản mẫu.");
        AccountRecord active = PRESERVE_SESSION.get() ? accounts.get(signedInEmail == null ? "" : signedInEmail.toLowerCase(Locale.ROOT)) : sample;
        if (active == null || (PRESERVE_SESSION.get() && !active.id.equals(signedInCustomerId)))
            throw new InvalidObjectException("Phiên khách hàng không hợp lệ.");
        signedInEmail = active.email; signedInName = active.name; signedInCustomerId = active.id;
        cart = carts.computeIfAbsent(active.id, key -> new LinkedHashMap<>());
        interactionConsent = consentByCustomer.getOrDefault(active.id, false);
        persistenceError = ""; dirty = false;
        rebuildAiModel();
    }

    public synchronized void attachRepository(StoreRepository repository) {
        this.repository = Objects.requireNonNull(repository); dirty = true; persistenceError = "";
    }

    public synchronized boolean flushData() {
        if (repository == null || !dirty) return true;
        try { repository.save(this); dirty = false; persistenceError = ""; return true; }
        catch (IOException ex) { persistenceError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); return false; }
    }

    public boolean persistent() { return repository != null; }
    public String persistenceError() { return persistenceError == null ? "" : persistenceError; }
    public String storageDescription() { return repository == null ? "Phiên tạm · dữ liệu sẽ mất khi đóng." : "Dữ liệu lưu trên máy · " + repository.dataFile(); }

    public synchronized void closeRepository() {
        if (repository != null) {
            try { repository.close(); } catch (IOException ignored) { }
            repository = null;
        }
        if (recommendationExecutor != null) recommendationExecutor.shutdownNow();
    }

    private static java.util.concurrent.ThreadPoolExecutor newRecommendationExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1), r -> {
                    Thread thread = new Thread(r, "techzone-recommendation"); thread.setDaemon(true); return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    private void seedAccounts() {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        accounts.put(CUSTOMER_EMAIL.toLowerCase(Locale.ROOT), new AccountRecord(CUSTOMER_ID, CUSTOMER_NAME, CUSTOMER_EMAIL,
                CUSTOMER_ADDRESS, salt, hashPassword("TechZone2026!".toCharArray(), salt)));
        signedInEmail = CUSTOMER_EMAIL;
        signedInName = CUSTOMER_NAME;
        signedInCustomerId = CUSTOMER_ID;
        carts.put(CUSTOMER_ID, cart);
    }

    private byte[] hashPassword(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("Không thể tạo thông tin xác thực.", e); }
        finally { spec.clearPassword(); }
    }

    public record AccountResult(boolean success, String message) { }

    public AccountResult registerCustomer(String name, String email, char[] password) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (cleanName.length() < 2 || cleanName.length() > 100) return new AccountResult(false, "Họ tên cần có từ 2 đến 100 ký tự.");
        if (!cleanEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) return new AccountResult(false, "Email chưa đúng định dạng.");
        if (password == null || password.length < 8) return new AccountResult(false, "Mật khẩu cần có ít nhất 8 ký tự.");
        if (accounts.containsKey(cleanEmail)) return new AccountResult(false, "Email này đã được đăng ký.");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] digest = hashPassword(password, salt);
        java.util.Arrays.fill(password, '\0');
        String id = "CUS-" + String.format(Locale.ROOT, "%03d", customerSequence++);
        accounts.put(cleanEmail, new AccountRecord(id, cleanName, cleanEmail, "", salt, digest));
        demoRoleAssignments.put(cleanEmail, Role.CUSTOMER);
        audit(cleanName, "Đăng ký tài khoản", "Tài khoản được tạo.");
        return new AccountResult(true, "Đăng ký thành công. Bạn có thể đăng nhập bằng tài khoản mới.");
    }

    public AccountResult authenticateCustomer(String email, char[] password) {
        String cleanEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        AccountRecord account = accounts.get(cleanEmail);
        if (account == null || password == null) return new AccountResult(false, "Email hoặc mật khẩu không chính xác.");
        byte[] candidate = hashPassword(password, account.salt);
        java.util.Arrays.fill(password, '\0');
        if (!MessageDigest.isEqual(candidate, account.passwordHash)) return new AccountResult(false, "Email hoặc mật khẩu không chính xác.");
        signedInEmail = account.email;
        signedInName = account.name;
        signedInCustomerId = account.id;
        cart = carts.computeIfAbsent(account.id, key -> new LinkedHashMap<>());
        interactionConsent = consentByCustomer.getOrDefault(account.id, false);
        audit(account.name, "Đăng nhập", "Xác thực thành công.");
        return new AccountResult(true, "Xin chào " + account.name + ". Đăng nhập thành công.");
    }

    public String signedInEmail() { return signedInEmail; }
    public String signedInName() { return signedInName; }
    public String signedInCustomerId() { return signedInCustomerId; }
    public String signedInAddress() {
        AccountRecord account = accounts.get(signedInEmail.toLowerCase(Locale.ROOT));
        return account == null ? "" : account.address;
    }
    public List<vn.techzone.domain.Customer> customerProfiles() {
        return accounts.values().stream().map(a -> new vn.techzone.domain.Customer(a.id,a.name,a.email,a.phoneNumber,a.address,"ACTIVE",null,null)).toList();
    }
    public synchronized String updateCustomerProfile(String name,String phone,String address) {
        AccountRecord current=accounts.get(signedInEmail.toLowerCase(Locale.ROOT));
        if(current==null)return "Không tìm thấy tài khoản hiện tại.";
        vn.techzone.domain.Customer updated;
        try { updated=new vn.techzone.domain.Customer(current.id,current.name,current.email,current.phoneNumber,current.address,"ACTIVE",null,null)
                .updateProfile(name,phone,address); }
        catch(IllegalArgumentException ex) { return "Tên và địa chỉ khách hàng không hợp lệ."; }
        AccountRecord next=new AccountRecord(current.id,updated.fullName(),current.email,updated.phoneNumber(),updated.address(),current.salt,current.passwordHash,
                current.createdAt,java.time.LocalDateTime.now());
        accounts.put(current.email.toLowerCase(Locale.ROOT),next);signedInName=next.name;dirty=true;
        audit(signedInName,"Cập nhật hồ sơ khách hàng","Thông tin liên hệ đã được cập nhật.");
        return "Đã cập nhật hồ sơ khách hàng.";
    }

    /** Password data remains salted PBKDF2 hashes; no plaintext is exported. */
    public record RelationalAccount(String id,String name,String email,String address,byte[] salt,byte[] passwordHash,String phoneNumber,
                                    java.time.LocalDateTime createdAt,java.time.LocalDateTime updatedAt) {
        public RelationalAccount(String id,String name,String email,String address,byte[] salt,byte[] passwordHash) {
            this(id,name,email,address,salt,passwordHash,"",null,null);
        }
        public RelationalAccount(String id,String name,String email,String address,byte[] salt,byte[] passwordHash,String phoneNumber) {
            this(id,name,email,address,salt,passwordHash,phoneNumber,null,null);
        }
        public RelationalAccount { salt=salt.clone(); passwordHash=passwordHash.clone(); }
        @Override public byte[] salt() { return salt.clone(); }
        @Override public byte[] passwordHash() { return passwordHash.clone(); }
    }
    public List<RelationalAccount> relationalAccounts() {
        return accounts.values().stream().map(a -> {
            java.time.LocalDateTime created=a.createdAt==null?java.time.LocalDateTime.now():a.createdAt;
            java.time.LocalDateTime updated=a.updatedAt==null?created:a.updatedAt;
            return new RelationalAccount(a.id,a.name,a.email,a.address,a.salt,a.passwordHash,a.phoneNumber,created,updated);
        }).toList();
    }
    public Map<String,List<CartItem>> relationalCarts() {
        Map<String,List<CartItem>> result=new LinkedHashMap<>();
        carts.forEach((customerId,items) -> result.put(customerId,items.values().stream().map(i -> new CartItem(i.variantId,i.quantity,i.unitPriceSnapshot,i.addedAt,i.updatedAt)).toList()));
        return result;
    }
    public void restoreRelationalAccounts(List<RelationalAccount> rows,Map<String,List<CartItem>> cartRows) throws IOException {
        restoreRelationalAccounts(rows,cartRows,signedInCustomerId);
    }
    public void restoreRelationalAccounts(List<RelationalAccount> rows,Map<String,List<CartItem>> cartRows,String activeCustomerId) throws IOException {
        Map<String,AccountRecord> nextAccounts=new LinkedHashMap<>(); Set<String> ids=new LinkedHashSet<>();
        for(RelationalAccount row:rows) {
            if(row==null || row.id()==null || row.name()==null || row.email()==null || row.address()==null
                    || row.salt().length!=16 || row.passwordHash().length!=32 || !ids.add(row.id()))
                throw new IOException("Tài khoản quan hệ không hợp lệ.");
            String email=row.email().toLowerCase(Locale.ROOT);
            if(nextAccounts.putIfAbsent(email,new AccountRecord(row.id(),row.name(),row.email(),row.phoneNumber(),row.address(),row.salt(),row.passwordHash(),row.createdAt(),row.updatedAt()))!=null)
                throw new IOException("Email tài khoản bị trùng.");
        }
        if(!nextAccounts.keySet().containsAll(accounts.keySet())) throw new IOException("Thiếu tài khoản trong SQLite.");
        AccountRecord active=nextAccounts.values().stream().filter(a->a.id.equals(activeCustomerId)).findFirst().orElse(null);
        if(active==null) throw new IOException("Tài khoản phiên không hợp lệ.");
        Map<String,Map<String,CartItem>> nextCarts=new LinkedHashMap<>();
        for(var entry:cartRows.entrySet()) {
            if(!ids.contains(entry.getKey())) throw new IOException("Giỏ hàng không có khách hàng tương ứng.");
            Map<String,CartItem> items=new LinkedHashMap<>();
            for(CartItem item:entry.getValue()) if(item==null || item.quantity<=0 || !products.containsKey(item.variantId)
                    || items.putIfAbsent(item.variantId,new CartItem(item.variantId,item.quantity,item.unitPriceSnapshot,item.addedAt,item.updatedAt))!=null)
                throw new IOException("Dòng giỏ hàng quan hệ không hợp lệ.");
            nextCarts.put(entry.getKey(),items);
        }
        if(!nextCarts.keySet().containsAll(carts.keySet())) throw new IOException("Thiếu giỏ hàng trong SQLite.");
        for(SalesOrder order:orders.values()) if(!ids.contains(order.customerId)) throw new IOException("Đơn hàng không có khách hàng tương ứng.");
        accounts.clear(); accounts.putAll(nextAccounts); carts.clear(); carts.putAll(nextCarts);
        cart=carts.computeIfAbsent(active.id,k -> new LinkedHashMap<>());
        signedInName=active.name; signedInEmail=active.email; signedInCustomerId=active.id;
        interactionConsent=consentByCustomer.getOrDefault(active.id,false);
    }

    public String updateTechnicalSpec(String variantId, String key, String value, String actor) {
        ProductVariant product = product(variantId);
        if (product == null || key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) return "Tên trường thông số không hợp lệ (chữ, số hoặc dấu gạch dưới).";
        if (value == null || value.isBlank()) return "Thông số không được để trống.";
        if (Set.of("minPsu", "wattage").contains(key) && !value.trim().matches("(?i)^[1-9][0-9]{0,4}\\s*(w|watt|watts)?$"))
            return "Thông số công suất phải là số dương, ví dụ 650 W.";
        String previous = product.specs.put(key, value.trim());
        if (!value.trim().equals(previous)) {
            product.updatedAt=LocalDateTime.now();
            List<String> affected = approvedPairs.stream()
                    .filter(pair -> pair.contains(variantId + "|") || pair.endsWith("|" + variantId)).toList();
            for (String pair : affected) {
                approvedPairs.remove(pair);
                String[] ids = pair.split("\\|");
                String otherId = ids[0].equals(variantId) ? ids[1] : ids[0];
                compatibilityReviews.add(0, new CompatibilityReview("CMP-" + (compatibilityReviews.size() + 15),
                        variantId, otherId, "Thông số " + key + " vừa thay đổi; cần xác minh lại cặp SKU.", LocalDateTime.now()));
            }
            syncCatalogIndexes();
            audit(actor, "Cập nhật catalogue kỹ thuật", product.sku + " · " + key + ": " + (previous == null ? "(mới)" : previous) + " → " + value.trim()
                    + ". Các cặp liên quan cần được rà soát lại.");
        }
        return "Đã cập nhật thông số catalogue. Các gợi ý liên quan được yêu cầu rà soát lại.";
    }

    private static Map<String, String> specs(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(values[i], values[i + 1]);
        return map;
    }

    public String setWarrantyDuration(String variantId, int months, String evidence, String actor) {
        ProductVariant product = product(variantId);
        if (product == null || !product.active) return "Không tìm thấy sản phẩm đang bán.";
        if (months < 1 || months > 120) return "Thời hạn bảo hành phải từ 1 đến 120 tháng.";
        if (evidence == null || evidence.isBlank()) return "Nhập nguồn hoặc căn cứ xác minh bảo hành.";
        int previous = product.warrantyMonths;
        product.warrantyMonths = months;
        product.updatedAt=LocalDateTime.now();
        audit(actor, "Cập nhật thời hạn bảo hành", product.sku + " · " + previous + " → " + months
                + " tháng · Căn cứ: " + evidence.trim());
        return "Đã cập nhật thời hạn bảo hành. Các yêu cầu đang chờ cần được xác minh lại.";
    }

    private ProductVariant addProduct(String id, String sku, String name, String category, String brand,
                                      String description, String color, long price, long salePrice,
                                      int warranty, int stock, int threshold, String... specificationPairs) {
        ProductVariant product = new ProductVariant(id, sku, name, category, brand, description, color,
                price, salePrice, warranty, specs(specificationPairs));
        products.put(id, product);
        balances.put(id, new InventoryBalance(id, stock, 0, 0, threshold, "KHO-HCM-01"));
        java.time.LocalDateTime seededAt=java.time.LocalDateTime.now();
        for (int i = 1; i <= stock; i++) {
            String serial = serialFor(id, i);
            issuedSerials.add(serial);
            unitsBySerial.put(serial, new InventoryUnit(serial,id,seededAt,"DEMO-INITIAL-"+id,"UNASSIGNED"));
        }
        return product;
    }

    private void seedProducts() {
        addProduct("CPU-14600K", "TZ-CPU-14600K", "Intel Core i5-14600K", "CPU", "Intel",
                "CPU hiệu năng cao cho gaming và sáng tạo nội dung.", "#5B81F4", 8490000, 7990000, 36, 12, 4,
                "socket", "LGA1700", "cores", "14 nhân", "tdp", "125 W");
        addProduct("CPU-7800X3D", "TZ-CPU-7800X3D", "AMD Ryzen 7 7800X3D", "CPU", "AMD",
                "Bộ xử lý gaming với bộ nhớ đệm 3D V-Cache.", "#B56CE0", 11490000, 10990000, 36, 8, 3,
                "socket", "AM5", "cores", "8 nhân", "tdp", "120 W");
        addProduct("CPU-14700K", "TZ-CPU-14700K", "Intel Core i7-14700K", "CPU", "Intel",
                "CPU đa nhiệm mạnh mẽ, socket LGA1700.", "#6788C8", 13990000, 13290000, 36, 6, 2,
                "socket", "LGA1700", "cores", "20 nhân", "tdp", "125 W");
        addProduct("MB-B760-D5", "TZ-MB-B760-D5", "Gigabyte B760M AORUS ELITE AX DDR5", "Mainboard", "Gigabyte",
                "Bo mạch chủ LGA1700, DDR5, Wi-Fi 6E.", "#32B79D", 5590000, 5190000, 36, 7, 3,
                "socket", "LGA1700", "memoryType", "DDR5", "formFactor", "Micro-ATX", "m2", "2 khe M.2");
        addProduct("MB-B760-D4", "TZ-MB-B760-D4", "MSI PRO B760M-P DDR4", "Mainboard", "MSI",
                "Bo mạch chủ LGA1700 nhỏ gọn cho cấu hình DDR4.", "#42A58B", 3690000, 3490000, 36, 9, 3,
                "socket", "LGA1700", "memoryType", "DDR4", "formFactor", "Micro-ATX", "m2", "2 khe M.2");
        addProduct("MB-B650", "TZ-MB-B650", "MSI MAG B650M MORTAR WIFI", "Mainboard", "MSI",
                "Bo mạch chủ AM5, DDR5, tản nhiệt VRM cao cấp.", "#37AA92", 6690000, 6290000, 36, 5, 6,
                "socket", "AM5", "memoryType", "DDR5", "formFactor", "Micro-ATX", "m2", "2 khe M.2");
        addProduct("RAM-D5-32", "TZ-RAM-D5-32", "Kingston Fury Beast 32GB DDR5 6000", "RAM", "Kingston",
                "Bộ kit 2 x 16 GB DDR5 tối ưu cho nền tảng mới.", "#E89D53", 2890000, 2690000, 36, 18, 5,
                "memoryType", "DDR5", "capacity", "32 GB", "speed", "6000 MT/s");
        addProduct("RAM-D4-16", "TZ-RAM-D4-16", "Corsair Vengeance LPX 16GB DDR4 3200", "RAM", "Corsair",
                "Bộ kit 2 x 8 GB DDR4 tương thích bo mạch DDR4.", "#D98B4D", 1390000, 1290000, 36, 15, 5,
                "memoryType", "DDR4", "capacity", "16 GB", "speed", "3200 MT/s");
        addProduct("GPU-4070S", "TZ-GPU-4070S", "ASUS Dual GeForce RTX 4070 SUPER 12GB", "GPU", "ASUS",
                "Card đồ họa 1440p với DLSS 3, cần nguồn từ 650 W.", "#7C74EC", 19990000, 18990000, 36, 5, 2,
                "vram", "12 GB", "minPsu", "650 W", "interface", "PCIe 4.0");
        addProduct("GPU-4060", "TZ-GPU-4060", "MSI GeForce RTX 4060 VENTUS 2X 8GB", "GPU", "MSI",
                "Card đồ họa nhỏ gọn, phù hợp chơi game Full HD.", "#7C74EC", 9490000, 8990000, 36, 10, 3,
                "vram", "8 GB", "minPsu", "550 W", "interface", "PCIe 4.0");
        addProduct("PSU-750", "TZ-PSU-750", "Corsair RM750e 750W 80+ Gold", "PSU", "Corsair",
                "Nguồn modular 750 W, chuẩn 80 Plus Gold.", "#D9AF54", 3290000, 3090000, 60, 8, 3,
                "wattage", "750 W", "efficiency", "80+ Gold", "modular", "Full modular");
        addProduct("PSU-550", "TZ-PSU-550", "Cooler Master MWE 550 Bronze V2", "PSU", "Cooler Master",
                "Nguồn 550 W cho cấu hình phổ thông.", "#D9AF54", 1490000, 1390000, 60, 7, 8,
                "wattage", "550 W", "efficiency", "80+ Bronze", "modular", "Non-modular");
        addProduct("SSD-990", "TZ-SSD-990", "Samsung 990 EVO Plus 1TB NVMe", "SSD", "Samsung",
                "SSD NVMe PCIe 4.0 tốc độ cao, dung lượng 1 TB.", "#43AFA5", 2490000, 2290000, 60, 13, 4,
                "capacity", "1 TB", "interface", "M.2 NVMe PCIe 4.0", "readSpeed", "7.150 MB/s");
        addProduct("MON-27", "TZ-MON-27", "LG UltraGear 27GP850-B 27 inch QHD", "Màn hình", "LG",
                "Màn hình IPS QHD 180 Hz, thời gian phản hồi 1 ms.", "#538AA2", 8990000, 8490000, 24, 6, 2,
                "size", "27 inch", "resolution", "2560 x 1440", "refreshRate", "180 Hz");
        addProduct("PHONE-S24", "TZ-PHONE-S24", "Samsung Galaxy S24 256GB", "Điện thoại", "Samsung",
                "Điện thoại flagship nhỏ gọn, bộ nhớ trong 256 GB.", "#6A83D2", 22990000, 21490000, 12, 7, 2,
                "capacity", "256 GB", "model", "Galaxy S24", "color", "Onyx Black");
        addProduct("CASE-S24", "TZ-CASE-S24", "Ốp lưng Samsung Galaxy S24 Clear Case", "Phụ kiện", "Samsung",
                "Ốp lưng trong suốt, thiết kế vừa vặn Galaxy S24.", "#6CBEAC", 390000, 350000, 6, 14, 4,
                "forModel", "Galaxy S24", "material", "Polycarbonate");
        addProduct("PHONE-IP16", "TZ-PHONE-IP16", "Apple iPhone 16 128GB", "Điện thoại", "Apple",
                "Điện thoại Apple thế hệ mới, bộ nhớ 128 GB.", "#6A83D2", 22990000, 21990000, 12, 8, 2,
                "capacity", "128 GB", "model", "iPhone 16", "color", "Ultramarine");
        addProduct("CASE-IP16", "TZ-CASE-IP16", "Ốp lưng Apple Silicone Case iPhone 16", "Phụ kiện", "Apple",
                "Ốp silicone chính hãng dành cho iPhone 16.", "#6CBEAC", 1290000, 1190000, 6, 11, 3,
                "forModel", "iPhone 16", "material", "Silicone");
        addProduct("MOUSE-G502", "TZ-MOUSE-G502", "Logitech G502 HERO Gaming Mouse", "Phụ kiện", "Logitech",
                "Chuột gaming có dây với cảm biến HERO 25K.", "#6CBEAC", 1490000, 1290000, 24, 12, 4,
                "connection", "USB", "sensor", "HERO 25K");
        addProduct("COOLER-PA120", "TZ-COOLER-PA120", "Thermalright Peerless Assassin 120 SE", "Tản nhiệt", "Thermalright",
                "Tản nhiệt khí tháp đôi, hỗ trợ LGA1700 và AM5.", "#5FAE9A", 1290000, 1190000, 24, 8, 3,
                "socketSupport", "LGA1700, AM5", "type", "Air cooler");
    }

    /** Imports curated source records and retires the former duplicate demo listings. */
    public int importRealCatalog() throws IOException {
        var resource = TechZoneStore.class.getResourceAsStream("/vn/techzone/catalog/real-products.tsv");
        if (resource == null) throw new IOException("Không tìm thấy danh mục sản phẩm thực tế.");
        int changes = 0;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] raw = line.split("\\t", -1), fields = new String[raw.length];
                for (int i = 0; i < raw.length; i++) fields[i] = new String(Base64.getDecoder().decode(raw[i]), java.nio.charset.StandardCharsets.UTF_8);
                if (fields.length < 6 || (fields.length - 6) % 2 != 0) throw new IOException("Dữ liệu danh mục không hợp lệ.");
                if (products.containsKey(fields[0])) continue;
                String[] pairs = Arrays.copyOfRange(fields, 6, fields.length);
                addProduct(fields[0], "TZ-" + fields[0], fields[1], fields[2], fields[3], fields[5],
                        "#6489bc", Long.parseLong(fields[4]), 0, 0, 3, 1, pairs);
                openingSellable.put(fields[0], 3);
                changes++;
            }
        }
        for (ProductVariant product : products.values()) {
            if (product.id.matches("DEMO-.+-[0-9]{2}") && product.active) {
                product.active = false;
                product.updatedAt=LocalDateTime.now();
                changes++;
            }
        }
        if (changes > 0) syncCatalogIndexes();
        return changes;
    }

    /** Replace the active catalogue with public GearVN listings while retaining history and local stock separately. */
    public int syncGearVNCatalog(List<GearVNCatalogProduct> listings) throws IOException {
        if(listings==null || listings.size()<50 || listings.size()>500)
            throw new IOException("GearVN crawl snapshot is incomplete; the current catalogue was kept.");
        Set<String> ids=new HashSet<>();
        int updated=0;
        for(GearVNCatalogProduct item:listings) {
            if(item==null || item.id()==null || !item.id().matches("GVN-[a-f0-9]{16}")
                    || item.name()==null || item.name().isBlank() || item.name().length()>300
                    || item.category()==null || item.category().isBlank()
                    || item.price()<=0 || item.price()>1_000_000_000L
                    || item.salePrice()<0 || (item.salePrice()>0 && item.salePrice()>=item.price())
                    || item.sourceUrl()==null || !item.sourceUrl().matches("https://gearvn\\.com/products/[a-z0-9-]{2,180}")
                    || item.imageUrl()==null || !item.imageUrl().matches("https://cdn\\.hstatic\\.net/[^\\s]{1,1000}")
                    || !Set.of("IN_STOCK","OUT_OF_STOCK","UNKNOWN").contains(item.availability())
                    || !ids.add(item.id()))
                throw new IOException("GearVN crawl snapshot failed product validation; the current catalogue was kept.");
        }
        for(GearVNCatalogProduct item:listings) {
            ProductVariant current=products.get(item.id());
            if(current==null) {
                Map<String,String> specs=new LinkedHashMap<>(item.specs());
                specs.put("sourceProvider","GEARVN");
                specs.put("sourceUrl",item.sourceUrl());
                specs.put("sourceImageUrl",item.imageUrl());
                specs.put("sourceFetchedAt",Objects.toString(item.fetchedAt(),""));
                specs.put("sourceAvailability",item.availability());
                current=new ProductVariant(item.id(),item.id(),item.name(),"TZ-"+item.id(),item.name(),
                        item.category(),item.brand(),item.description(),"",item.price(),item.salePrice(),0,specs);
                current.createdAt=item.fetchedAt()==null?LocalDateTime.now():item.fetchedAt();
                current.updatedAt=current.createdAt;
                products.put(current.id,current);
                balances.put(current.id,new InventoryBalance(current.id,0,0,0,1,"GEARVN-EXTERNAL"));
                openingSellable.put(current.id,0);
            } else {
                current.price=item.price();
                current.salePrice=item.salePrice();
                current.active=true;
                current.updatedAt=item.fetchedAt()==null?LocalDateTime.now():item.fetchedAt();
                current.specs.clear();
                current.specs.putAll(item.specs());
                current.specs.put("sourceProvider","GEARVN");
                current.specs.put("sourceUrl",item.sourceUrl());
                current.specs.put("sourceImageUrl",item.imageUrl());
                current.specs.put("sourceFetchedAt",Objects.toString(item.fetchedAt(),""));
                current.specs.put("sourceAvailability",item.availability());
            }
            updated++;
        }
        for(ProductVariant product:products.values()) {
            if(!ids.contains(product.id) && product.active) {
                product.active=false;
                product.updatedAt=LocalDateTime.now();
            }
        }
        if(updated>0) syncCatalogIndexes();
        return updated;
    }

    /** One explicitly sourced pair; a prior staff review always takes precedence. */
    public boolean seedVerifiedManufacturerPair() {
        String cpu = "CPU-7800X3D", board = "PCP-LwNxFT", key = pairKey(cpu, board);
        ProductVariant left = product(cpu), right = product(board);
        if (left == null || right == null || !left.active || !right.active
                || !"AMD Ryzen 7 7800X3D".equals(left.name)
                || !"MSI MAG B650 TOMAHAWK WIFI".equals(right.name)
                || approvedPairs.contains(key) || !safePair(cpu, board)
                || compatibilityReviews.stream().anyMatch(r -> pairKey(r.primaryVariantId,r.accessoryVariantId).equals(key))) return false;
        String source = "AMD: https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-7-7800X3d.html ; MSI: https://www.msi.com/Motherboard/MAG-B650-TOMAHAWK-WIFI/Specification";
        CompatibilityReview review = new CompatibilityReview("CMP-SOURCE-AM5-01",cpu,board,"AMD Ryzen 7000 / AM5 trên MSI B650; kiểm tra phiên bản BIOS theo nhà sản xuất.",LocalDateTime.now());
        review.status="Đã xác minh · cho phép"; review.reviewedBy="Dữ liệu nguồn hãng"; review.reviewNote=source;review.reviewedAt=LocalDateTime.now();
        compatibilityReviews.add(review); approvedPairs.add(key); syncCatalogIndexes();
        audit("Dữ liệu nguồn hãng", "Nhập cặp tương thích có nguồn", cpu + " ↔ " + board + " · " + source);
        return true;
    }

    public boolean seedVerifiedRyzen7600Pairs() {
        ProductVariant cpu=product("PCP-yXmmP6");
        if (cpu==null || !cpu.active || !"AMD Ryzen 5 7600".equals(cpu.name)) return false;
        String oldSocket=cpu.spec("socket");
        if (!oldSocket.isBlank() && !"AM5".equalsIgnoreCase(oldSocket)) return false;
        String cpuSource="https://www.amd.com/en/products/processors/desktops/ryzen/7000-series/amd-ryzen-5-7600.html";
        boolean changed=false;
        if (oldSocket.isBlank()) {
            cpu.specs.put("socket","AM5"); cpu.specs.put("Nguồn socket",cpuSource);
            cpu.updatedAt=LocalDateTime.now();
            audit("Dữ liệu nguồn hãng","Bổ sung socket sản phẩm",cpu.sku+" · AM5 · "+cpuSource); changed=true;
        }
        String[][] boards={
                {"PCP-LwNxFT","MSI MAG B650 TOMAHAWK WIFI","https://www.msi.com/Motherboard/MAG-B650-TOMAHAWK-WIFI/Specification"},
                {"MB-B650","MSI MAG B650M MORTAR WIFI","https://au.msi.com/Motherboard/MAG-B650M-MORTAR-WIFI/Specification"}};
        for(int i=0;i<boards.length;i++) {
            String boardId=boards[i][0], key=pairKey(cpu.id,boardId);
            ProductVariant board=product(boardId);
            if(board==null || !board.active || !boards[i][1].equals(board.name) || approvedPairs.contains(key)
                    || !safePair(cpu.id,boardId)
                    || compatibilityReviews.stream().anyMatch(r -> pairKey(r.primaryVariantId,r.accessoryVariantId).equals(key))) continue;
            String source="AMD: "+cpuSource+" ; MSI: "+boards[i][2];
            CompatibilityReview review=new CompatibilityReview("CMP-SOURCE-7600-0"+(i+1),cpu.id,boardId,
                    "AMD Ryzen 7000 / AM5; kiểm tra BIOS theo nhà sản xuất.",LocalDateTime.now());
            review.status="Đã xác minh · cho phép"; review.reviewedBy="Dữ liệu nguồn hãng"; review.reviewNote=source;review.reviewedAt=LocalDateTime.now();
            compatibilityReviews.add(review); approvedPairs.add(key);
            audit("Dữ liệu nguồn hãng","Nhập cặp tương thích có nguồn",cpu.id+" ↔ "+boardId+" · "+source);
            changed=true;
        }
        if(changed) syncCatalogIndexes();
        return changed;
    }

    /** Add only exact, manufacturer-sourced storage facts; never replace existing user data. */
    public boolean seedVerifiedStorageFacts() {
        boolean changed=false;
        String[][] boards={
                {"MB-B760-D4","MSI PRO B760M-P DDR4","https://www.msi.com/Motherboard/PRO-B760M-P-DDR4/Specification"},
                {"MB-B650","MSI MAG B650M MORTAR WIFI","https://www.msi.com/Motherboard/MAG-B650M-MORTAR-WIFI/Specification"}};
        for(String[] row:boards) {
            ProductVariant p=product(row[0]);
            if(p==null || !row[1].equals(p.name)) continue;
            changed |= addSourcedFact(p,"m2Protocol","PCIe NVMe",row[2]);
            changed |= addSourcedFact(p,"m2Sizes","2280 2260",row[2]);
        }
        ProductVariant ssd=product("SSD-990");
        if(ssd!=null && "Samsung 990 EVO Plus 1TB NVMe".equals(ssd.name))
            changed |= addSourcedFact(ssd,"formFactor","M.2 2280","https://www.samsung.com/uk/memory-storage/nvme-ssd/990-evo-plus-1tb-nvme-pcie-gen-4-mz-v9s1t0bw/");
        if(changed) { syncCatalogIndexes(); audit("Dữ liệu nguồn hãng","Bổ sung thông số M.2 có nguồn","Không ghi đè thông số đang tồn tại."); }
        return changed;
    }
    private static boolean addSourcedFact(ProductVariant p,String key,String value,String source) {
        if(!p.spec(key).isBlank() && !p.spec(key).equals(value))return false;
        if(!p.spec("source."+key).isBlank())return false;
        if(p.spec(key).isBlank())p.specs.put(key,value);
        p.specs.put("source."+key,source);
        p.updatedAt=LocalDateTime.now();
        return true;
    }

    private void approve(String primary, String accessory) { approvedPairs.add(pairKey(primary, accessory)); }
    private static String pairKey(String left, String right) {
        return vn.techzone.domain.CompatibilityRule.key(left,right);
    }

    private void seedCompatibility() {
        approve("CPU-14600K", "MB-B760-D5"); approve("CPU-14600K", "MB-B760-D4");
        approve("CPU-14700K", "MB-B760-D5"); approve("CPU-14700K", "MB-B760-D4");
        approve("CPU-7800X3D", "MB-B650");
        approve("MB-B760-D5", "RAM-D5-32"); approve("MB-B760-D4", "RAM-D4-16");
        approve("MB-B650", "RAM-D5-32");
        approve("GPU-4070S", "PSU-750"); approve("GPU-4060", "PSU-550"); approve("GPU-4060", "PSU-750");
        approve("MB-B760-D5", "SSD-990"); approve("MB-B760-D4", "SSD-990"); approve("MB-B650", "SSD-990");
        approve("PHONE-S24", "CASE-S24"); approve("PHONE-IP16", "CASE-IP16");
        approve("MON-27", "MOUSE-G502");
        for (ProductVariant cpu : products.values()) if ("CPU".equals(cpu.category)) {
            for (ProductVariant cooler : products.values()) if ("Tản nhiệt".equals(cooler.category)) approve(cpu.id, cooler.id);
        }
    }

    private void seedOrders() {
        SalesOrder pendingPick = new SalesOrder("ORD-1042", "TZO-2610-1042", CUSTOMER_ID, CUSTOMER_NAME,
                CUSTOMER_ADDRESS, "Ví điện tử", "seed-key-1042", "", LocalDateTime.now().minusHours(4));
        addSeedLine(pendingPick, "CPU-14600K", 1, false);
        addSeedLine(pendingPick, "MB-B760-D5", 1, false);
        addSeedLine(pendingPick, "RAM-D5-32", 1, false);
        pendingPick.subtotal = 7990000 + 5190000 + 2690000;
        pendingPick.tax = pendingPick.subtotal / 10;
        pendingPick.shippingFee = 0;
        pendingPick.total = pendingPick.subtotal + pendingPick.tax;
        pendingPick.status = OrderStatus.CONFIRMED;
        pendingPick.paymentReference = "PAY-DEMO-1042";
        pendingPick.paidAt = pendingPick.createdAt;
        orders.put(pendingPick.id, pendingPick);

        SalesOrder shipped = new SalesOrder("ORD-1038", "TZO-2610-1038", CUSTOMER_ID, CUSTOMER_NAME,
                "18 Lê Lợi, Q. 1, TP. Hồ Chí Minh", "Thẻ nội địa", "seed-key-1038", "", LocalDateTime.now().minusDays(2));
        addSeedLine(shipped, "GPU-4060", 1, true);
        addSeedLine(shipped, "PSU-550", 1, true);
        shipped.subtotal = 10380000; shipped.tax = 1038000; shipped.total = 11418000;
        shipped.status = OrderStatus.SHIPPED; shipped.paymentReference = "PAY-DEMO-1038";
        shipped.paidAt = shipped.createdAt;
        shipped.trackingCode = "TZVN900104288"; shipped.carrier = "Viettel Post";
        shipped.packageWeightGrams = 2400; shipped.packageDimensions = "40 x 30 x 20 cm";
        orders.put(shipped.id, shipped);

        SalesOrder delivered = new SalesOrder("ORD-0897", "TZO-2609-0897", CUSTOMER_ID, CUSTOMER_NAME,
                "12 Trần Phú, Q. Hải Châu, Đà Nẵng", "Chuyển khoản", "seed-key-0897", "", LocalDateTime.now().minusDays(24));
        addSeedLine(delivered, "PHONE-S24", 1, true);
        delivered.subtotal = 21490000; delivered.tax = 2149000; delivered.total = 23639000;
        delivered.status = OrderStatus.DELIVERED; delivered.paymentReference = "PAY-DEMO-0897";
        delivered.paidAt = delivered.createdAt;
        delivered.trackingCode = "TZVN899970014"; delivered.carrier = "GHN";
        delivered.packageWeightGrams = 500; delivered.packageDimensions = "20 x 15 x 10 cm";
        delivered.deliveredAt = LocalDateTime.now().minusDays(22);
        orders.put(delivered.id, delivered);
        for (String serial : delivered.lines.get(0).serials) {
            ProductVariant p = product("PHONE-S24");
            warranties.put(serial, new Warranty(serial, p.id, delivered.id, delivered.customerId,
                    delivered.deliveredAt, delivered.deliveredAt.plusMonths(p.warrantyMonths), WarrantyStatus.ACTIVE));
        }
        SalesOrder old = new SalesOrder("ORD-OLD", "TZO-2408-0100", CUSTOMER_ID, CUSTOMER_NAME,
                CUSTOMER_ADDRESS, "Chuyển khoản", "seed-key-old", "", LocalDateTime.now().minusMonths(16));
        addSeedLine(old, "PHONE-IP16", 1, true);
        old.subtotal = old.lines.get(0).lineTotal(); old.tax = old.subtotal / 10; old.total = old.subtotal + old.tax;
        old.status = OrderStatus.DELIVERED; old.paymentReference = "PAY-DEMO-OLD"; old.paidAt = old.createdAt; old.deliveredAt = LocalDateTime.now().minusMonths(15);
        orders.put(old.id, old);
        for (String serial : old.lines.get(0).serials) {
            ProductVariant p = product("PHONE-IP16");
            warranties.put(serial, new Warranty(serial, p.id, old.id, old.customerId, old.deliveredAt,
                    old.deliveredAt.plusMonths(p.warrantyMonths), WarrantyStatus.EXPIRED));
        }
        orderSequence = 1043;
    }

    private void addSeedLine(SalesOrder order, String variantId, int quantity, boolean allocated) {
        ProductVariant p = product(variantId);
        InventoryBalance b = balance(variantId);
        OrderLine line = new OrderLine(variantId, quantity, p.effectivePrice());
        line.reserveQuantity(quantity);
        if (allocated) {
            line.allocateQuantity(quantity);
            b.reserve(quantity);
            b.allocate(quantity);
            for (int i = 0; i < quantity; i++) {
                InventoryUnit unit = findAvailableUnit(variantId);
                if (unit != null) {
                    unit.status = UnitStatus.ALLOCATED;
                    unit.orderId = order.id;
                    line.serials.add(unit.serial);
                }
            }
        } else {
            b.reserve(quantity);
            for (int i = 0; i < quantity; i++) {
                InventoryUnit unit = findAvailableUnit(variantId);
                if (unit != null) { unit.status = UnitStatus.RESERVED; unit.orderId = order.id; }
            }
        }
        order.lines.add(line);
    }

    private void seedReviews() {
        reviews.add(new String[]{"Nguyễn Hoàng Nam", "Mainboard B760 DDR5", "Cấu hình rất ổn, đóng gói kỹ.", "5", "Đã xác minh"});
        reviews.add(new String[]{"Lê Thảo Vy", "RTX 4060 Ventus", "Chạy mát, shop tư vấn nguồn phù hợp.", "5", "Đã xác minh"});
        reviews.add(new String[]{"Võ Đức Minh", "Kingston Fury DDR5", "Đúng thông số, giao hàng nhanh.", "4", "Đã xác minh"});
    }

    public List<ProductVariant> products() { return new ArrayList<>(products.values()); }
    public List<vn.techzone.domain.Product> productFamilies() {
        Map<String,vn.techzone.domain.Product> families=new LinkedHashMap<>();
        for(ProductVariant variant:products.values()) {
            var family=families.computeIfAbsent(variant.productId(),id->new vn.techzone.domain.Product(id,variant.productName(),variant.category,variant.brand,variant.description));
            family.addVariant(variant);
        }
        return List.copyOf(families.values());
    }
    /** Rehydrate the core entities from relational rows after validating all references. */
    public void restoreRelationalCore(List<ProductVariant> productRows, List<InventoryBalance> balanceRows,
                                      List<InventoryUnit> unitRows, List<SalesOrder> orderRows) throws IOException {
        Map<String,ProductVariant> nextProducts=new LinkedHashMap<>();
        Map<String,InventoryBalance> nextBalances=new LinkedHashMap<>();
        Map<String,InventoryUnit> nextUnits=new LinkedHashMap<>();
        Map<String,SalesOrder> nextOrders=new LinkedHashMap<>();
        Map<String,String> nextKeys=new LinkedHashMap<>();
        for(ProductVariant p:productRows) if(p==null || p.id==null || nextProducts.putIfAbsent(p.id,p)!=null)
            throw new IOException("Dữ liệu sản phẩm quan hệ không hợp lệ.");
        for(InventoryBalance b:balanceRows) if(b==null || !nextProducts.containsKey(b.variantId)
                || b.available<0 || b.reserved<0 || b.allocated<0 || nextBalances.putIfAbsent(b.variantId,b)!=null)
            throw new IOException("Dữ liệu tồn kho quan hệ không hợp lệ.");
        if(!nextProducts.keySet().equals(nextBalances.keySet())) throw new IOException("Sản phẩm thiếu số dư tồn kho.");
        // Existing catalogue rows must not silently disappear during relational rehydration.
        if(!nextProducts.keySet().containsAll(products.keySet())) throw new IOException("Thiếu hàng sản phẩm trong SQLite.");
        for(InventoryUnit unit:unitRows) if(unit==null || unit.serial==null || unit.status==null
                || !nextProducts.containsKey(unit.variantId) || nextUnits.putIfAbsent(unit.serial,unit)!=null)
            throw new IOException("Dữ liệu Serial/IMEI quan hệ không hợp lệ.");
        for(SalesOrder order:orderRows) {
            if(order==null || order.id==null || nextOrders.putIfAbsent(order.id,order)!=null)
                throw new IOException("Dữ liệu đơn hàng quan hệ không hợp lệ.");
            if(order.idempotencyKey==null || order.idempotencyKey.isBlank()
                    || nextKeys.putIfAbsent(order.idempotencyKey,order.id)!=null)
                throw new IOException("Mã idempotency của đơn không hợp lệ.");
            for(OrderLine line:order.lines) if(!nextProducts.containsKey(line.variantId) || line.quantity<=0
                    || line.reservedQuantity<0 || line.allocatedQuantity<0
                    || line.reservedQuantity+line.allocatedQuantity>line.quantity)
                throw new IOException("Dòng đơn hàng quan hệ không hợp lệ.");
        }
        if(!nextOrders.keySet().containsAll(orders.keySet()) || !nextUnits.keySet().containsAll(unitsBySerial.keySet()))
            throw new IOException("Thiếu đơn hàng hoặc Serial/IMEI trong SQLite.");
        for(Map<String,CartItem> oneCart:carts.values()) for(CartItem item:oneCart.values())
            if(!nextProducts.containsKey(item.variantId)) throw new IOException("Giỏ hàng tham chiếu sản phẩm không tồn tại.");
        products.clear(); products.putAll(nextProducts);
        balances.clear(); balances.putAll(nextBalances);
        unitsBySerial.clear(); unitsBySerial.putAll(nextUnits); issuedSerials.addAll(nextUnits.keySet());
        orders.clear(); orders.putAll(nextOrders);
        orderByIdempotencyKey.clear(); orderByIdempotencyKey.putAll(nextKeys);
        openingSellable.clear();
        for(InventoryBalance b:nextBalances.values()) openingSellable.put(b.variantId,b.available+b.reserved);
        rebuildAiModel();
    }

    /** Reject a write if summary counters disagree with the actual physical units. */
    public void validateInventoryIntegrity() throws IOException {
        for(ProductVariant p:products.values()) {
            InventoryBalance b=balances.get(p.id);
            if(b==null) throw new IOException("Thiếu tồn kho cho " + p.sku);
            long available=0,reserved=0,allocated=0;
            for(InventoryUnit u:unitsBySerial.values()) if(p.id.equals(u.variantId)) {
                if(u.status==UnitStatus.AVAILABLE) available++;
                else if(u.status==UnitStatus.RESERVED) reserved++;
                else if(u.status==UnitStatus.ALLOCATED) allocated++;
            }
            if(b.available!=available || b.reserved!=reserved || b.allocated!=allocated)
                throw new IOException("Tồn kho và Serial/IMEI không khớp cho " + p.sku + ".");
        }
        for(InventoryUnit u:unitsBySerial.values()) if(u.status==UnitStatus.RESERVED || u.status==UnitStatus.ALLOCATED) {
            SalesOrder order=orders.get(u.orderId);
            if(order==null || order.lines.stream().noneMatch(line -> line.variantId.equals(u.variantId)))
                throw new IOException("Serial/IMEI không có dòng đơn hàng tương ứng: " + u.serial + ".");
        }
        for(SalesOrder order:orders.values()) for(OrderLine line:order.lines) {
            long reserved=unitsBySerial.values().stream().filter(u -> order.id.equals(u.orderId)
                    && line.variantId.equals(u.variantId) && u.status==UnitStatus.RESERVED).count();
            Set<String> allocated=new LinkedHashSet<>();
            for(InventoryUnit unit:unitsBySerial.values()) if(order.id.equals(unit.orderId)
                    && line.variantId.equals(unit.variantId) && unit.status==UnitStatus.ALLOCATED) allocated.add(unit.serial);
            if(reserved!=line.reservedQuantity || allocated.size()!=line.allocatedQuantity
                    || line.serials.size()!=allocated.size() || !allocated.equals(new LinkedHashSet<>(line.serials)))
                throw new IOException("Số lượng giữ/cấp và Serial/IMEI không khớp cho đơn " + order.number + ".");
        }
    }

    public void restoreRelationalSupport(List<Warranty> warrantyRows,List<RmaCase> rmaRows) throws IOException {
        Map<String,Warranty> nextWarranties=new LinkedHashMap<>();
        Map<String,RmaCase> nextRmas=new LinkedHashMap<>();
        for(Warranty w:warrantyRows) {
            InventoryUnit unit=w==null?null:unitsBySerial.get(w.serial);
            SalesOrder order=w==null?null:orders.get(w.orderId);
            if(w==null || unit==null || order==null || unit.status!=UnitStatus.ALLOCATED || !w.orderId.equals(unit.orderId)
                    || order.status!=OrderStatus.DELIVERED || !unit.variantId.equals(w.variantId)
                    || !order.customerId.equals(w.customerId)
                    || order.lines.stream().noneMatch(line -> line.variantId.equals(w.variantId) && line.serials.contains(w.serial))
                    || nextWarranties.putIfAbsent(w.serial,w)!=null)
                throw new IOException("Bảo hành không khớp Serial/IMEI hoặc đơn hàng.");
        }
        for(RmaCase r:rmaRows) {
            Warranty w=r==null?null:nextWarranties.get(r.serial);
            if(r==null || w==null || !w.customerId.equals(r.customerId) || nextRmas.putIfAbsent(r.id,r)!=null)
                throw new IOException("Phiếu RMA không khớp bảo hành hoặc khách hàng.");
        }
        if(!nextWarranties.keySet().containsAll(warranties.keySet()) || !nextRmas.keySet().containsAll(rmas.keySet()))
            throw new IOException("Thiếu bảo hành hoặc phiếu RMA trong SQLite.");
        warranties.clear(); warranties.putAll(nextWarranties); rmas.clear(); rmas.putAll(nextRmas);
    }

    public void validateSupportIntegrity() throws IOException {
        for(Warranty w:warranties.values()) {
            InventoryUnit unit=unitsBySerial.get(w.serial); SalesOrder order=orders.get(w.orderId);
            if(unit==null || order==null || unit.status!=UnitStatus.ALLOCATED || !w.orderId.equals(unit.orderId)
                    || order.status!=OrderStatus.DELIVERED || !unit.variantId.equals(w.variantId) || !order.customerId.equals(w.customerId)
                    || order.lines.stream().noneMatch(line -> line.variantId.equals(w.variantId) && line.serials.contains(w.serial))
                    || w.activatedAt==null || w.expiresAt==null || w.status==null)
                throw new IOException("Bảo hành không khớp Serial/IMEI hoặc đơn hàng.");
        }
        for(RmaCase item:rmas.values()) {
            Warranty w=warranties.get(item.serial);
            if(w==null || !w.customerId.equals(item.customerId) || item.createdAt==null)
                throw new IOException("Phiếu RMA không khớp bảo hành hoặc khách hàng.");
        }
    }
    public ProductVariant product(String id) { return products.get(id); }
    public InventoryBalance balance(String id) { return balances.get(id); }
    public int available(String id) { return balances.containsKey(id) ? balances.get(id).available : 0; }
    public Map<String, CartItem> cart() { return cart; }
    public List<SalesOrder> orders() {
        return orders.values().stream().sorted(Comparator.comparing((SalesOrder o) -> o.createdAt).reversed()).toList();
    }
    public List<Warranty> warranties() { return new ArrayList<>(warranties.values()); }
    public List<RmaCase> rmas() { return rmas.values().stream().toList(); }
    public List<WarrantyVerification> warrantyVerifications() { return new ArrayList<>(warrantyVerifications.values()); }
    public RmaCase rma(String id) { return rmas.get(id); }
    public List<ShipmentEvent> shipmentEvents(String orderId) {
        return shipmentEvents.getOrDefault(orderId, List.of()).stream()
                .sorted(Comparator.comparing(e -> e.occurredAt)).toList();
    }
    public List<ConsultationNote> consultationNotes(String orderId) {
        return consultationNotes.stream().filter(n -> n.orderId.equals(orderId))
                .sorted(Comparator.comparing((ConsultationNote n) -> n.createdAt).reversed()).toList();
    }
    public List<AppNotification> notifications(Role role) {
        return notifications.stream().filter(n -> "ALL".equals(n.audience)
                || ("STAFF".equals(n.audience) && role != Role.CUSTOMER)
                || (n.audience.equals("CUSTOMER:" + signedInCustomerId) && role == Role.CUSTOMER)).toList();
    }
    public int unreadNotificationCount(Role role) { return (int) notifications(role).stream().filter(n -> !n.read).count(); }
    public void markNotificationsRead(Role role) {
        for (AppNotification n : notifications(role)) if (!n.read) { n.read = true; dirty = true; }
    }
    public Map<String, Role> demoRoleAssignments() { return new LinkedHashMap<>(demoRoleAssignments); }
    public List<String> shipmentMilestones() { return List.of("Đang trung chuyển", "Đang giao tới bạn", "Đã giao thành công"); }
    public String nextShipmentMilestone(String orderId) {
        List<ShipmentEvent> history = shipmentEvents(orderId);
        if (history.stream().anyMatch(e -> "Đã giao thành công".equals(e.milestone))) return "";
        if (history.stream().noneMatch(e -> "Đang trung chuyển".equals(e.milestone))) return "Đang trung chuyển";
        if (history.stream().noneMatch(e -> "Đang giao tới bạn".equals(e.milestone))) return "Đang giao tới bạn";
        return "Đã giao thành công";
    }
    public List<InteractionEvent> interactionEvents() { return interactionEvents.stream().toList(); }
    public List<RecommendationImpression> recommendationImpressions() { return List.copyOf(recommendationImpressions); }
    public void restoreRelationalImpressions(List<RecommendationImpression> rows) throws IOException {
        Set<String> customerIds=new HashSet<>(); for(AccountRecord account:accounts.values()) customerIds.add(account.id);
        Set<String> ids=new HashSet<>();
        for(RecommendationImpression row:rows) if(row==null || row.id==null || row.requestId==null || row.shownAt==null
                || row.strategy==null || row.modelVersion==null || row.rank<1 || !customerIds.contains(row.customerId)
                || !products.containsKey(row.primaryId) || !products.containsKey(row.accessoryId) || !ids.add(row.id))
            throw new IOException("Lượt hiển thị gợi ý quan hệ không hợp lệ.");
        for(RecommendationImpression old:recommendationImpressions) if(!ids.contains(old.id))
            throw new IOException("Thiếu lượt hiển thị gợi ý trong SQLite.");
        recommendationImpressions.clear(); recommendationImpressions.addAll(rows);
    }
    public record RelationalPair(String left,String right) { }
    public List<RelationalPair> relationalCompatibilityPairs() {
        return approvedPairs.stream().map(key -> { String[] parts=key.split("\\|",2); return new RelationalPair(parts[0],parts[1]); }).toList();
    }
    public void restoreRelationalDiscovery(List<RelationalPair> pairs,List<InteractionEvent> events) throws IOException {
        Set<String> nextPairs=new LinkedHashSet<>();
        for(RelationalPair pair:pairs) if(pair==null || pair.left()==null || pair.right()==null || pair.left().equals(pair.right())
                || !products.containsKey(pair.left()) || !products.containsKey(pair.right()) || !nextPairs.add(pairKey(pair.left(),pair.right())))
            throw new IOException("Cặp tương thích quan hệ không hợp lệ.");
        if(!nextPairs.containsAll(approvedPairs) || events.size()<interactionEvents.size())
            throw new IOException("Thiếu cặp tương thích hoặc sự kiện trong SQLite.");
        Set<String> customerIds=new HashSet<>();for(AccountRecord account:accounts.values())customerIds.add(account.id);
        for(InteractionEvent event:events) if(event==null || event.type==null || event.detail==null || event.occurredAt==null
                || (event.customerId!=null && !customerIds.contains(event.customerId))
                || (event.variantId!=null && !products.containsKey(event.variantId)))
            throw new IOException("Sự kiện tương tác quan hệ không hợp lệ.");
        approvedPairs.clear(); approvedPairs.addAll(nextPairs); interactionEvents.clear(); interactionEvents.addAll(events);
    }
    public List<AuditEntry> auditEntries() { return auditEntries.stream().toList(); }
    public List<RestockRequest> restockRequests() { return restockRequests.stream().toList(); }
    public List<CompatibilityReview> compatibilityReviews() { return compatibilityReviews.stream().toList(); }
    public void restoreRelationalCompatibilityReviews(List<CompatibilityReview> rows) throws IOException {
        Set<String> ids=new HashSet<>();
        for(CompatibilityReview review:rows) if(review==null || review.id==null || review.reason==null || review.createdAt==null
                || review.status==null || review.reviewedBy==null || review.reviewNote==null
                || !products.containsKey(review.primaryVariantId) || !products.containsKey(review.accessoryVariantId)
                || !ids.add(review.id)) throw new IOException("Dữ liệu rà soát tương thích không hợp lệ.");
        for(CompatibilityReview existing:compatibilityReviews) if(!ids.contains(existing.id))
            throw new IOException("Thiếu lịch sử rà soát tương thích trong SQLite.");
        compatibilityReviews.clear();compatibilityReviews.addAll(rows);
    }
    public List<String[]> reviews() { return new ArrayList<>(reviews); }
    public record RelationalAuxiliaryState(List<WarrantyVerification> warrantyVerifications,
                                           List<ShipmentEvent> shipmentEvents,
                                           List<AppNotification> notifications,
                                           List<ConsultationNote> consultationNotes,
                                           List<AuditEntry> auditEntries,
                                           List<RestockRequest> restockRequests,
                                           List<SupplierReturn> supplierReturns,
                                           List<String[]> reviews,
                                           Map<String,Boolean> consents,
                                           Map<String,Integer> supplierLeadDays,
                                           Set<String> paymentCallbackIds,
                                           Set<String> escalatedPaymentOrders,
                                           Set<String> deliveryEvents,
                                           Set<String> shipmentCallbackIds,
                                           Set<String> lowStockNotified,
                                           Map<String,Set<String>> recommendedCartItems,
                                           Map<String,Set<String>> recommendedOrderItems,
                                           Map<String,String> lastSearchFilters,
                                           int recommendationLimit,long monthlySalesTarget,int promotionPercent,
                                           int catalogRevision,int searchIndexRevision,int recommendationIndexRevision,
                                           long orderSequence,int rmaSequence,int verificationSequence,
                                           int notificationSequence,int noteSequence,int customerSequence) { }

    /** Relational persistence contract for operational records outside the 17 report entities. */
    public RelationalAuxiliaryState relationalAuxiliaryState() {
        List<ShipmentEvent> allShipmentEvents=shipmentEvents.values().stream().flatMap(List::stream).toList();
        Map<String,Set<String>> cartAttributions=new LinkedHashMap<>(),orderAttributions=new LinkedHashMap<>();
        recommendedCartItems.forEach((k,v)->cartAttributions.put(k,Set.copyOf(v)));
        recommendedOrderItems.forEach((k,v)->orderAttributions.put(k,Set.copyOf(v)));
        return new RelationalAuxiliaryState(List.copyOf(warrantyVerifications.values()),allShipmentEvents,List.copyOf(notifications),
                List.copyOf(consultationNotes),List.copyOf(auditEntries),List.copyOf(restockRequests),List.copyOf(supplierReturns),
                reviews.stream().map(String[]::clone).toList(),Map.copyOf(consentByCustomer),Map.copyOf(supplierLeadDays),
                Set.copyOf(paymentCallbackIds),Set.copyOf(escalatedPaymentOrders),Set.copyOf(deliveryEvents),
                Set.copyOf(shipmentCallbackIds),Set.copyOf(lowStockNotified),Map.copyOf(cartAttributions),Map.copyOf(orderAttributions),
                Map.copyOf(lastSearchFilters),recommendationLimit,monthlySalesTarget,promotionPercent,catalogRevision,searchIndexRevision,
                recommendationIndexRevision,orderSequence,rmaSequence,
                verificationSequence,notificationSequence,noteSequence,customerSequence);
    }

    public void restoreRelationalAuxiliaryState(RelationalAuxiliaryState state) {
        Objects.requireNonNull(state);
        warrantyVerifications.clear();for(WarrantyVerification v:state.warrantyVerifications())warrantyVerifications.put(v.id,v);
        shipmentEvents.clear();for(ShipmentEvent e:state.shipmentEvents())shipmentEvents.computeIfAbsent(e.orderId,k->new ArrayList<>()).add(e);
        notifications.clear();notifications.addAll(state.notifications());consultationNotes.clear();consultationNotes.addAll(state.consultationNotes());
        auditEntries.clear();auditEntries.addAll(state.auditEntries());restockRequests.clear();restockRequests.addAll(state.restockRequests());
        supplierReturns.clear();supplierReturns.addAll(state.supplierReturns());reviews.clear();
        for(String[] review:state.reviews())reviews.add(review.clone());
        consentByCustomer.clear();consentByCustomer.putAll(state.consents());supplierLeadDays.clear();supplierLeadDays.putAll(state.supplierLeadDays());
        paymentCallbackIds.clear();paymentCallbackIds.addAll(state.paymentCallbackIds());
        escalatedPaymentOrders.clear();escalatedPaymentOrders.addAll(state.escalatedPaymentOrders());
        deliveryEvents.clear();deliveryEvents.addAll(state.deliveryEvents());shipmentCallbackIds.clear();shipmentCallbackIds.addAll(state.shipmentCallbackIds());
        lowStockNotified.clear();lowStockNotified.addAll(state.lowStockNotified());
        recommendedCartItems.clear();state.recommendedCartItems().forEach((k,v)->recommendedCartItems.put(k,new LinkedHashSet<>(v)));
        recommendedOrderItems.clear();state.recommendedOrderItems().forEach((k,v)->recommendedOrderItems.put(k,new LinkedHashSet<>(v)));
        lastSearchFilters.clear();lastSearchFilters.putAll(state.lastSearchFilters());recommendationLimit=state.recommendationLimit();
        monthlySalesTarget=state.monthlySalesTarget();promotionPercent=state.promotionPercent();orderSequence=state.orderSequence();
        catalogRevision=state.catalogRevision();searchIndexRevision=state.searchIndexRevision();recommendationIndexRevision=state.recommendationIndexRevision();
        rmaSequence=state.rmaSequence();verificationSequence=state.verificationSequence();notificationSequence=state.notificationSequence();
        noteSequence=state.noteSequence();customerSequence=state.customerSequence();
        interactionConsent=consentByCustomer.getOrDefault(signedInCustomerId,false);
    }

    public List<AppNotification> allNotifications() { return List.copyOf(notifications); }
    public List<ConsultationNote> allConsultationNotes() { return List.copyOf(consultationNotes); }
    public List<ShipmentEvent> allShipmentEvents() { return shipmentEvents.values().stream().flatMap(List::stream).toList(); }
    public boolean interactionConsent() { return interactionConsent; }
    public void setInteractionConsent(boolean value) {
        interactionConsent = value;
        consentByCustomer.put(signedInCustomerId, value);
        if (!value) {
            String prefix = "customer=" + signedInCustomerId + " · ";
            interactionEvents.removeIf(event -> event.detail.startsWith(prefix));
            recommendationImpressions.removeIf(event -> event.customerId.equals(signedInCustomerId));
            lastSearchFilters.remove(signedInCustomerId); recommendedCartItems.remove(signedInCustomerId);
            recommendedOrderItems.keySet().removeIf(id -> orders.containsKey(id) && orders.get(id).customerId.equals(signedInCustomerId));
        }
        rebuildAiModel();
        audit(signedInName, "Cập nhật quyền riêng tư", value ? "Đã bật ghi nhận sự kiện tương tác." : "Đã tắt và xóa sự kiện của tài khoản hiện tại.");
    }

    public boolean searchServiceAvailable() { return searchServiceAvailable; }
    public boolean recommendationServiceAvailable() { return recommendationServiceAvailable; }
    public int recommendationLimit() { return recommendationLimit >= 1 && recommendationLimit <= 8 ? recommendationLimit : 3; }
    public void setRecommendationLimit(int limit, String actor) {
        if (limit < 1 || limit > 8) throw new IllegalArgumentException("Số gợi ý phải từ 1 đến 8.");
        if (recommendationLimit() == limit) return;
        recommendationLimit = limit;
        audit(actor, "Cấu hình gợi ý", "Tối đa " + limit + " sản phẩm tương thích.");
    }
    public int catalogRevision() { return catalogRevision; }
    public int searchIndexRevision() { return searchIndexRevision; }
    public int recommendationIndexRevision() { return recommendationIndexRevision; }
    public String rankingModelVersion() { return rankingModelVersion; }

    public void setSearchServiceAvailable(boolean available, String actor) {
        searchServiceAvailable = available;
        audit(actor, "Đổi trạng thái AI Search", available ? "Mô hình TF-IDF cục bộ sẵn sàng." : "Mô phỏng sự cố; truy vấn dùng fallback từ khóa + thông số.");
    }

    public void setRecommendationServiceAvailable(boolean available, String actor) {
        recommendationServiceAvailable = available;
        audit(actor, "Đổi trạng thái recommender", available ? "Mô hình giỏ hàng cục bộ sẵn sàng." : "Mô phỏng sự cố; dùng fallback tương thích + tồn kho.");
    }

    public String assignDemoRole(String email, Role role, String actor) {
        String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!demoRoleAssignments.containsKey(key)) return "Chỉ tài khoản đã đăng ký mới được gán vai trò.";
        if (role == null) return "Hãy chọn vai trò hợp lệ.";
        if (demoRoleAssignments.get(key) == Role.ADMIN && role != Role.ADMIN
                && demoRoleAssignments.values().stream().filter(r -> r == Role.ADMIN).count() <= 1)
            return "Không thể gỡ vai trò quản trị viên cuối cùng.";
        Role previous = demoRoleAssignments.put(key, role);
        audit(actor, "Cập nhật vai trò", key + " · " + previous + " → " + role + ".");
        return "Đã cập nhật quyền cho " + key + ".";
    }

    public String searchMode(boolean semanticRequested) {
        if (!searchServiceAvailable) return "Fallback từ khóa + thông số";
        return semanticRequested ? "AI tìm kiếm TF-IDF · cosine" : "Từ khóa + thông số";
    }

    public String recommendationStrategy() {
        if (!recommendationServiceAvailable) return "Fallback phổ biến + tương thích · dịch vụ lỗi";
        String owner = "customer=" + signedInCustomerId + " · ";
        boolean hasContext = interactionConsent && interactionEvents.stream().anyMatch(e -> e.detail.startsWith(owner)
                && Set.of("product_view", "search_click", "cart_add", "purchase").contains(e.type));
        return hasContext ? "AI gợi ý · giỏ hàng và ngữ cảnh phiên" : "AI gợi ý · học từ giỏ hàng mẫu";
    }

    public String recommendationStrategy(String primaryId) {
        if (recommendationServiceAvailable && !aiEngine.hasBasketEvidence(primaryId)) return "Fallback phổ biến + tương thích · sản phẩm mới";
        return recommendationStrategy();
    }

    public int aiVocabularySize() { return aiEngine.vocabularySize(); }
    public int aiTrainingBasketCount() { return aiEngine.basketCount(); }

    public String refreshSearchIndexes(String actor) {
        syncCatalogIndexes();
        audit(actor, "Đồng bộ chỉ mục tìm kiếm và gợi ý", "Catalog revision " + catalogRevision + " · mô hình cục bộ đã đồng bộ.");
        return "Đã đồng bộ chỉ mục tìm kiếm và gợi ý với catalogue revision " + catalogRevision + ".";
    }

    private void syncCatalogIndexes() {
        catalogRevision++;
        searchIndexRevision = catalogRevision;
        recommendationIndexRevision = catalogRevision;
        rebuildAiModel();
    }

    private void rebuildAiModel() {
        List<List<String>> permittedBaskets = orders.values().stream().filter(this::isPaid)
                .filter(o -> consentByCustomer.getOrDefault(o.customerId, false))
                .map(o -> o.lines.stream().map(line -> line.variantId).toList()).toList();
        Map<String, Integer> popularity = new HashMap<>();
        List<List<String>> baskets = new ArrayList<>(LocalAiEngine.sampleBaskets()); baskets.addAll(permittedBaskets);
        for (List<String> basket : baskets) for (String id : new HashSet<>(basket))
            if (products.containsKey(id)) popularity.merge(id, 1, Integer::sum);
        recommendationPopularity = Map.copyOf(popularity);
        aiEngine.fit(products.values(), permittedBaskets);
    }

    public record AiMetrics(long queries, long searchImpressions, long searchClicks, long searchNoResults, long filterChanges,
                            long searchFallbacks, long recommendationImpressions, long recommendationClicks,
                            long recommendationFallbacks, long cartAdds, long purchases,
                            long recommendationCartAdds, long recommendationPurchases) { }

    public AiMetrics aiMetrics() {
        return new AiMetrics(eventCount("search_query"), eventCount("search_impression"), eventCount("search_click"),
                eventCount("search_no_result"), eventCount("search_filter_change"), eventCount("search_fallback"), eventCount("recommendation_impression"),
                eventCount("recommendation_click"), eventCount("recommendation_fallback"), eventCount("cart_add"), eventCount("purchase"),
                eventCount("recommendation_cart_add"), eventCount("recommendation_purchase"));
    }

    private long eventCount(String type) { return interactionEvents.stream().filter(e -> e.type.equals(type)).count(); }

    public record AiEvaluation(int labelledQueries, double semanticRecallAt3, double keywordRecallAt3,
                               double semanticNdcgAt3, double keywordNdcgAt3,
                               int compatibilityViolations, int recommendationChecks, String searchMode) { }

    public record RecommendationEvaluation(int trainBaskets, int testBaskets, int cases, int k,
            double recallAtK, double ndcgAtK, double coverage, int violations, int checkedItems,
            int latencySamples, int fallbackSamples, double p50InferenceMs, double p95InferenceMs, double p95TotalMs) { }

    /** Synthetic chronological split; it is not evidence of real-customer quality or production SLA. */
    public RecommendationEvaluation runRecommendationEvaluation() {
        List<List<String>> baskets = LocalAiEngine.sampleBaskets();
        int split = Math.max(1, baskets.size() * 4 / 5);
        List<List<String>> training = baskets.subList(0, split), holdout = baskets.subList(split, baskets.size());
        LocalAiEngine evaluationModel = new LocalAiEngine();
        evaluationModel.fit(products.values(), training, false);
        Map<String, Integer> popularity = new HashMap<>();
        for (List<String> basket : training) for (String id : new HashSet<>(basket)) popularity.merge(id, 1, Integer::sum);
        int k = recommendationLimit(), cases = 0, checks = 0, violations = 0;
        double recall = 0, ndcg = 0;
        Set<String> recommended = new HashSet<>(), eligibleUnion = new HashSet<>();
        for (List<String> basket : holdout) for (String primary : new LinkedHashSet<>(basket)) {
            if (product(primary) == null) continue;
            List<ProductVariant> eligible = products.values().stream()
                    .filter(p -> !p.id.equals(primary) && p.active && available(p.id) > 0 && compatible(primary, p.id)).toList();
            Set<String> relevant = new HashSet<>(basket); relevant.remove(primary);
            relevant.retainAll(eligible.stream().map(p -> p.id).collect(java.util.stream.Collectors.toSet()));
            if (relevant.isEmpty()) continue;
            boolean cold = !evaluationModel.hasBasketEvidence(primary);
            Comparator<ProductVariant> comparator = cold
                    ? Comparator.comparingInt((ProductVariant p) -> popularity.getOrDefault(p.id, 0)).reversed().thenComparing(p -> p.id)
                    : Comparator.comparingDouble((ProductVariant p) -> evaluationModel.recommendationScore(primary, p.id, Set.of(primary)))
                            .reversed().thenComparing(p -> p.id);
            List<ProductVariant> top = eligible.stream().sorted(comparator).limit(k).toList();
            eligible.forEach(p -> eligibleUnion.add(p.id));
            int hits = 0; double dcg = 0;
            for (int i = 0; i < top.size(); i++) {
                ProductVariant item = top.get(i); checks++; recommended.add(item.id);
                if (!compatible(primary, item.id) || available(item.id) <= 0) violations++;
                if (relevant.contains(item.id)) { hits++; dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); }
            }
            double ideal = 0; for (int i = 0; i < Math.min(k, relevant.size()); i++) ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
            recall += (double) hits / relevant.size(); ndcg += ideal == 0 ? 0 : dcg / ideal; cases++;
        }
        List<Double> inference = new ArrayList<>(), totals = new ArrayList<>(); int fallbackSamples = 0;
        Set<String> benchmarkCart = Set.of("CPU-14600K", "GPU-4060");
        for (int i = 0; i < 35; i++) {
            RecommendationResult result = recommend("CPU-14600K", benchmarkCart, k);
            if (i < 5) continue;
            totals.add(result.totalMs());
            if (result.strategy().startsWith("Fallback") || result.inferenceMs() <= 0) fallbackSamples++;
            else inference.add(result.inferenceMs());
        }
        return new RecommendationEvaluation(training.size(), holdout.size(), cases, k,
                cases == 0 ? 0 : recall / cases, cases == 0 ? 0 : ndcg / cases,
                eligibleUnion.isEmpty() ? 0 : (double) recommended.size() / eligibleUnion.size(), violations, checks,
                inference.size(), fallbackSamples, percentile(inference, .50), percentile(inference, .95), percentile(totals, .95));
    }

    private static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(fraction * sorted.size()) - 1)));
    }

    private record LabelledQuery(String query, String category, String socket, String memory, Set<String> relevantIds) { }

    public AiEvaluation runOfflineAiEvaluation() {
        List<LabelledQuery> fixtures = List.of(
                new LabelledQuery("LGA1700 DDR5 motherboard", "Mainboard", "LGA1700", "DDR5", Set.of("MB-B760-D5")),
                new LabelledQuery("LGA1700 DDR4 motherboard", "Mainboard", "LGA1700", "DDR4", Set.of("MB-B760-D4")),
                new LabelledQuery("AM5 DDR5 motherboard", "Mainboard", "AM5", "DDR5", Set.of("MB-B650")),
                new LabelledQuery("Galaxy S24", "Điện thoại", "", "", Set.of("PHONE-S24")),
                new LabelledQuery("RTX 4060", "GPU", "", "", Set.of("GPU-4060")),
                new LabelledQuery("NVMe 1TB", "SSD", "", "", Set.of("SSD-990")),
                new LabelledQuery("quiet cooler for CPU", "Tản nhiệt", "", "", Set.of("COOLER-PA120")));
        double[] semantic = evaluateQueries(fixtures, true);
        double[] keyword = evaluateQueries(fixtures, false);
        int checks = 0, violations = 0;
        for (String primary : List.of("MB-B760-D5", "GPU-4070S", "PHONE-S24", "CPU-14600K")) {
            for (ProductVariant candidate : recommendations(primary, Set.of(), 8)) {
                checks++;
                if (assessPair(primary,candidate.id).status()==PairCompatibility.Status.CONFLICT || available(candidate.id) <= 0) violations++;
            }
        }
        return new AiEvaluation(fixtures.size(), semantic[0], keyword[0], semantic[1], keyword[1],
                violations, checks, searchMode(true));
    }

    private double[] evaluateQueries(List<LabelledQuery> fixtures, boolean semantic) {
        double recallSum = 0, ndcgSum = 0;
        for (LabelledQuery fixture : fixtures) {
            List<ProductVariant> results = search(fixture.query, fixture.category, fixture.socket, fixture.memory, 0, semantic);
            List<ProductVariant> top = results.stream().limit(3).toList();
            long hits = top.stream().filter(p -> fixture.relevantIds.contains(p.id)).count();
            recallSum += (double) hits / fixture.relevantIds.size();
            double dcg = 0;
            for (int i = 0; i < top.size(); i++) if (fixture.relevantIds.contains(top.get(i).id)) dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            int idealCount = Math.min(3, fixture.relevantIds.size());
            double idcg = 0;
            for (int i = 0; i < idealCount; i++) idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            ndcgSum += idcg == 0 ? 0 : dcg / idcg;
        }
        return new double[]{recallSum / fixtures.size(), ndcgSum / fixtures.size()};
    }

    public List<ProductVariant> search(String query,String category,String socket,String memory,long maxPrice,boolean semanticRanking) {
        return search(query,category,socket,memory,maxPrice,semanticRanking,Map.of());
    }

    public List<ProductVariant> search(String query,String category,String socket,String memory,long maxPrice,
                                       boolean semanticRanking,Map<String,String> technicalFilters) {
        return catalogSearchService.search(products.values(),this::available,query,category,socket,memory,maxPrice,
                semanticRanking,technicalFilters,searchServiceAvailable,aiEngine==null?null:aiEngine::rankSearch,null);
    }

    public List<ProductVariant> searchWithEmbeddings(String query,String category,String socket,String memory,long maxPrice,
                                                      Map<String,String> technicalFilters,Map<String,Double> scores) {
        return catalogSearchService.search(products.values(),this::available,query,category,socket,memory,maxPrice,
                true,technicalFilters,searchServiceAvailable,aiEngine==null?null:aiEngine::rankSearch,Objects.requireNonNull(scores));
    }
    public record RecommendationResult(List<ProductVariant> items, String strategy, double inferenceMs, double totalMs) implements vn.techzone.application.port.RankingPort.Result { }
    public List<ProductVariant> recommendations(String primaryId, Set<String> inCart, int topK) {
        return recommend(primaryId, inCart, topK).items();
    }

    /** Hard filters precede ranking; timeout or failure falls back to eligible popular items. */
    public RecommendationResult recommend(String primaryId, Set<String> inCart, int topK) {
        return recommendWithEmbeddings(primaryId, inCart, topK, null, "");
    }

    /** Pretrained similarity contributes only to ranking after compatibility and stock filters. */
    public RecommendationResult recommendWithEmbeddings(String primaryId, Set<String> inCart, int topK,
                                                         Map<String,Double> embeddings, String model) {
        Set<String> selected = inCart == null ? Set.of() : Set.copyOf(inCart);
        if (product(primaryId) == null || !product(primaryId).active) return new RecommendationResult(List.of(), "Không có sản phẩm chính", 0, 0);
        List<ProductVariant> eligible = eligibleRecommendations(primaryId, selected);
        Set<String> context = recommendationContext(); context.addAll(selected);
        boolean basketEvidence = aiEngine != null && aiEngine.hasBasketEvidence(primaryId);
        RecommendationRankingService.Result ranked = recommendationRankingService.rank(primaryId, eligible, context,
                topK, recommendationServiceAvailable, aiEngine != null, basketEvidence, embeddings, model,
                recommendationPopularity,
                (candidate, scoringContext) -> aiEngine.recommendationScore(primaryId, candidate.id, scoringContext),
                recommendationExecutor);
        return new RecommendationResult(ranked.items(), ranked.strategy(), ranked.inferenceMs(), ranked.totalMs());
    }

    /** Only pairs in the reviewed compatibility matrix with current matching facts may be ranked. */
    public List<ProductVariant> eligibleRecommendations(String primaryId, Set<String> inCart) {
        return recommendationEligibilityService.eligible(primaryId, inCart, products.values(), this::available,
                this::approvedCompatible);
    }

    private Set<String> recommendationContext() {
        Set<String> context = new LinkedHashSet<>(cart.keySet());
        if (!interactionConsent) return context;
        String owner = "customer=" + signedInCustomerId + " · ";
        for (InteractionEvent event : interactionEvents) if ((signedInCustomerId.equals(event.customerId) || (event.customerId==null && event.detail.startsWith(owner)))
                && Set.of("product_view", "search_click", "cart_add").contains(event.type)) {
            if(event.variantId!=null && products.containsKey(event.variantId)) context.add(event.variantId);
            else for (ProductVariant product : products.values())
                if (event.detail.contains(product.sku) || event.detail.contains(product.name)) context.add(product.id);
        }
        return context;
    }

    public void recordSearch(String originalQuery, String filters, List<ProductVariant> candidates,
                             String strategy, double latencyMs) {
        if (!interactionConsent) return;
        String safeQuery = originalQuery == null ? "" : originalQuery.trim();
        String requestId=UUID.randomUUID().toString();
        String previousFilters = lastSearchFilters.put(signedInCustomerId, filters);
        if (previousFilters != null && !previousFilters.equals(filters))
            recordStructuredInteraction("search_filter_change","filters="+filters,null,requestId,safeQuery,filters,"RELEVANCE",1);
        String skuList = candidates.stream().map(p -> p.sku).collect(java.util.stream.Collectors.joining(", "));
        recordStructuredInteraction("search_query","query=\""+safeQuery+"\" · filters="+filters+" · strategy="+strategy
                +" · latency="+String.format(Locale.ROOT,"%.2fms",latencyMs)+" · SKUs="+skuList,null,requestId,safeQuery,filters,"RELEVANCE",1);
        if (candidates.isEmpty()) recordStructuredInteraction("search_no_result","query=\""+safeQuery+"\" · filters="+filters,null,requestId,safeQuery,filters,"RELEVANCE",1);
        if (strategy.startsWith("Fallback")) recordStructuredInteraction("search_fallback",strategy+" · query=\""+safeQuery+"\"",null,requestId,safeQuery,filters,"RELEVANCE",1);
        for (int i = 0; i < candidates.size(); i++) {
            ProductVariant p = candidates.get(i);
            recordStructuredInteraction("search_impression","rank="+(i+1)+" · "+p.sku+" · query=\""+safeQuery+"\"",p.id,requestId,safeQuery,filters,"RELEVANCE",1);
        }
    }

    public void recordSearchClick(String variantId) {
        ProductVariant p = product(variantId);
        if (p != null) recordInteraction("search_click", p.sku + " · " + p.name,p.id);
    }

    public void recordProductView(String variantId) {
        ProductVariant p = product(variantId);
        if (p != null) recordInteraction("product_view", p.sku + " · " + p.name,p.id);
    }

    public void recordRecommendationImpressions(String primaryId, List<ProductVariant> candidates, String strategy) {
        recordRecommendationImpressions(primaryId,candidates,strategy,UUID.randomUUID().toString());
    }

    private void recordRecommendationImpressions(String primaryId,List<ProductVariant> candidates,String strategy,String requestId) {
        if (!interactionConsent) return;
        if (strategy.startsWith("Fallback")) recordInteraction("recommendation_fallback", strategy + " · primary=" + primaryId);
        if (candidates.isEmpty()) return;
        Set<String> scoringContext=Set.copyOf(recommendationContext());
        for (int i = 0; i < candidates.size(); i++) {
            ProductVariant p = candidates.get(i);
            recordInteraction("recommendation_impression", "primary=" + primaryId + " · rank=" + (i + 1) + " · " + p.sku + " · " + strategy,p.id);
            if(product(primaryId)!=null && product(p.id)!=null) {
                double score=strategy.startsWith("Fallback") || aiEngine==null
                        ? recommendationPopularity.getOrDefault(p.id,0)
                        : aiEngine.recommendationScore(primaryId,p.id,scoringContext);
                recommendationImpressions.add(new RecommendationImpression(UUID.randomUUID().toString(),requestId,signedInCustomerId,
                        primaryId,p.id,i+1,score,strategy,rankingModelVersion,LocalDateTime.now()));
            }
        }
    }

    public void recordRecommendationResult(String primaryId, RecommendationResult result) {
        if (!interactionConsent || result == null) return;
        String requestId=UUID.randomUUID().toString();
        recordInteraction("recommendation_request", "request=" + requestId + " · primary=" + primaryId + " · strategy=" + result.strategy()
                + " · inferenceMs=" + String.format(Locale.ROOT, "%.2f", result.inferenceMs())
                + " · totalMs=" + String.format(Locale.ROOT, "%.2f", result.totalMs())
                + " · count=" + result.items().size());
        recordRecommendationImpressions(primaryId, result.items(), result.strategy(),requestId);
    }

    public void recordRecommendationClick(String primaryId, String accessoryId) {
        ProductVariant primary = product(primaryId), accessory = product(accessoryId);
        if (primary != null && accessory != null) recordInteraction("recommendation_click", primary.sku + " → " + accessory.sku,accessory.id);
    }

    public PairCompatibility.Result assessPair(String leftId,String rightId) {
        String key=pairKey(leftId,rightId);
        for(CompatibilityReview review:compatibilityReviews) if(pairKey(review.primaryVariantId,review.accessoryVariantId).equals(key)) {
            if(review.status.startsWith("Đã chặn") || review.status.equals("Chờ rà soát"))
                return new PairCompatibility.Result(PairCompatibility.Status.CONFLICT,"Cặp đang bị chặn hoặc chờ rà soát kỹ thuật.");
            if(review.status.startsWith("Đã xác minh")) break;
        }
        return PairCompatibility.assess(product(leftId),product(rightId));
    }

    public boolean compatible(String leftId, String rightId) {
        return assessPair(leftId,rightId).status()==PairCompatibility.Status.MATCH;
    }

    /** The stored approval never overrides a conflict, pending review or missing current facts. */
    public boolean approvedCompatible(String leftId,String rightId) {
        boolean stored=approvedPairs.contains(pairKey(leftId,rightId));
        vn.techzone.domain.CompatibilityRule rule=new vn.techzone.domain.CompatibilityRule(leftId,rightId,stored);
        return rule.approved() && rule.matchesVariants(leftId,rightId) && compatible(leftId,rightId);
    }

    public String compatibilityDescription(String leftId, String rightId) {
        ProductVariant left = product(leftId), right = product(rightId);
        if (left == null || right == null) return "Không tìm thấy sản phẩm";
        if (!CompatibilityPolicy.needsReviewedRule(left, right)) return "Không áp dụng";
        var result=assessPair(leftId,rightId);
        return switch(result.status()) { case MATCH -> "Khớp thông số đã kiểm tra; chưa bảo đảm toàn bộ"; case UNKNOWN -> "Chưa đủ dữ liệu"; case CONFLICT -> "Xung đột hoặc bị chặn"; case NOT_APPLICABLE -> "Không áp dụng"; };
    }

    public String recommendationCompatibilityReason(String leftId, String rightId) {
        ProductVariant a = product(leftId), b = product(rightId);
        if (a == null || b == null) return "Không tìm thấy sản phẩm";
        PairCompatibility.Result assessment=assessPair(leftId,rightId);
        if(assessment.status()==PairCompatibility.Status.UNKNOWN) return "Chưa xác minh · "+assessment.detail();
        if(assessment.status()!=PairCompatibility.Status.MATCH) return assessment.detail();
        return "Khớp một phần · "+assessment.detail()+" Cần đối chiếu thêm thông số còn thiếu.";
    }

    public String validateCart() {
        if (cart.isEmpty()) return "Giỏ hàng đang trống.";
        for (CartItem item : cart.values()) {
            ProductVariant p = product(item.variantId);
            if (p == null || !p.active) return "Có sản phẩm không còn được bán. Vui lòng cập nhật giỏ hàng.";
            if (item.quantity <= 0 || item.quantity > available(item.variantId)) {
                return p.name + " chỉ còn " + available(item.variantId) + " sản phẩm có thể bán.";
            }
            long physicalUnits = unitsBySerial.values().stream().filter(u -> u.variantId.equals(item.variantId)
                    && u.status == UnitStatus.AVAILABLE).count();
            if (physicalUnits < item.quantity) return p.name + " có số Serial/IMEI sẵn sàng thấp hơn sổ kho; cần kiểm kê trước khi đặt.";
        }
        for (CartItem left : cart.values()) for (CartItem right : cart.values()) {
            if (left.variantId.equals(right.variantId)) continue;
            ProductVariant a = product(left.variantId), b = product(right.variantId);
            if (!CompatibilityPolicy.needsReviewedRule(a, b)) continue;
            if (!approvedCompatible(a.id,b.id))
                return "Chưa xác minh tương thích hoặc cặp đang bị chặn: " + a.name + " và " + b.name + ". Vui lòng yêu cầu kỹ thuật rà soát.";
        }
        List<ProductVariant> selected=cart.values().stream().map(item->product(item.variantId)).filter(Objects::nonNull).toList();
        for(var check:PhysicalCompatibility.applicable(selected)) if(check.status().equals("fail"))
            return "Cấu hình có xung đột " + check.name() + ": " + check.detail();
        return "";
    }

    public String cartCompatibilityWarning() {
        List<String> missing=new ArrayList<>();
        List<CartItem> items=new ArrayList<>(cart.values());
        for(int i=0;i<items.size();i++) for(int j=i+1;j<items.size();j++) {
            ProductVariant a=product(items.get(i).variantId), b=product(items.get(j).variantId);
            if(a!=null && b!=null && CompatibilityPolicy.needsReviewedRule(a,b) && !approvedCompatible(a.id,b.id))
                missing.add(a.category+" / "+b.category);
        }
        List<ProductVariant> selected=items.stream().map(item->product(item.variantId)).filter(Objects::nonNull).toList();
        List<String> physical=PhysicalCompatibility.applicable(selected).stream().filter(c->c.status().equals("unknown")).map(PhysicalCompatibility.Check::name).toList();
        if(missing.isEmpty() && physical.isEmpty())return "";
        String pairText=missing.isEmpty()?"":"Chưa được duyệt hoặc thiếu dữ liệu: "+String.join(", ",missing)+". ";
        String physicalText=physical.isEmpty()?"":"Chưa xác minh: "+String.join(", ",physical)+". ";
        return pairText+physicalText+"Hãy yêu cầu kỹ thuật rà soát trước khi mua thực tế.";
    }

    public String addToCart(String variantId, int quantity) {
        ProductVariant p = product(variantId);
        if (p == null || !p.active) return "Sản phẩm không khả dụng.";
        if (quantity < 1) return "Số lượng phải lớn hơn 0.";
        int existing = cart.containsKey(variantId) ? cart.get(variantId).quantity : 0;
        if ((long) existing + quantity > available(variantId)) return "Tồn kho hiện có không đủ cho số lượng này.";
        new vn.techzone.domain.ShoppingCart(cart).addItem(variantId, quantity,p.effectivePrice());
        recordInteraction("cart_add", p.name,p.id);
        audit(signedInName, "Thêm vào giỏ", p.name + " × " + quantity);
        return "Đã thêm " + p.name + " vào giỏ hàng.";
    }

    public String addRecommendedToCart(String primaryId, String variantId, int quantity) {
        ProductVariant candidate = product(variantId);
        ProductVariant primary=product(primaryId);
        if (candidate == null || primary==null || !CompatibilityPolicy.needsReviewedRule(primary,candidate)
                || !approvedCompatible(primaryId,variantId)
                || !recommendationEligibilityService.compatibleWithSelected(candidate, cart.keySet(), products.values(), this::approvedCompatible))
            return "Sản phẩm chưa được duyệt hoặc không phù hợp với cấu hình giỏ hàng.";
        int before = cart.containsKey(variantId) ? cart.get(variantId).quantity : 0;
        String result = addToCart(variantId, quantity);
        int after = cart.containsKey(variantId) ? cart.get(variantId).quantity : 0;
        if (interactionConsent && after > before) {
            recommendedCartItems.computeIfAbsent(signedInCustomerId, key -> new LinkedHashSet<>()).add(variantId);
            recordInteraction("recommendation_cart_add", primaryId + " → " + variantId,variantId);
        }
        return result;
    }

    public String updateCartQuantity(String variantId, int quantity) {
        if (!cart.containsKey(variantId)) return "Sản phẩm không có trong giỏ.";
        if (quantity <= 0) {
            cart.remove(variantId);
            Set<String> tracked = recommendedCartItems.get(signedInCustomerId);
            if (tracked != null) tracked.remove(variantId);
            dirty = true;
            return "Đã xóa sản phẩm khỏi giỏ.";
        }
        if (quantity > available(variantId)) return "Tồn kho chỉ còn " + available(variantId) + ".";
        cart.get(variantId).updateQuantity(quantity);
        dirty = true;
        return "Đã cập nhật số lượng.";
    }

    public long cartSubtotal() {
        return new vn.techzone.domain.ShoppingCart(cart).calculateSubtotal(id -> product(id).effectivePrice());
    }

    public long promotionDiscount(String code, long subtotal) {
        if (code == null) return 0;
        return switch (normalize(code).toUpperCase(Locale.ROOT)) {
            case "TECHZONE5" -> Math.min(subtotal * promotionPercent() / 100, 1_000_000);
            case "WELCOME500" -> subtotal >= 5_000_000 ? 500_000 : 0;
            default -> 0;
        };
    }

    public int promotionPercent() { return promotionPercent == 0 ? 5 : promotionPercent; }
    public CheckoutResult checkout(String address, String paymentMethod, String promotionCode, PaymentVerdict verdict) {
        return checkout(address, paymentMethod, promotionCode, verdict, "idem-" + UUID.randomUUID());
    }

    @Override public synchronized CheckoutResult checkout(String address, String paymentMethod, String promotionCode, PaymentVerdict verdict, String idempotencyKey) {
        return (CheckoutResult) checkoutWorkflowService.execute(this, address, paymentMethod, promotionCode, verdict, idempotencyKey);
    }

    public record CheckoutResult(SalesOrder order, String message, boolean confirmed) implements vn.techzone.application.port.OrderPort.Result { }

    @Override public SalesOrder orderForIdempotencyKey(String key) { return orders.get(orderByIdempotencyKey.get(key)); }
    @Override public String checkoutStatusLabel(OrderStatus status) { return localizedStatus(status); }
    @Override public List<CartItem> checkoutCartItems() { return new ArrayList<>(cart.values()); }
    @Override public long checkoutSequence() { return orderSequence; }
    @Override public void updateCurrentShippingAddress(String address) {
        AccountRecord customer = accounts.get(signedInEmail.toLowerCase(Locale.ROOT));
        if (customer != null) customer.address = address;
    }
    @Override public void appendReservedCheckoutLine(SalesOrder order, CartItem item, long unitPrice, long lineDiscount) {
        OrderLine line = new OrderLine(item.variantId, item.quantity, unitPrice);
        line.reserveQuantity(item.quantity);
        line.discountAmount = lineDiscount;
        order.lines.add(line);
        InventoryBalance inventory = balance(item.variantId);
        inventory.reserve(item.quantity);
        for (int i = 0; i < item.quantity; i++) {
            InventoryUnit unit = findAvailableUnit(item.variantId);
            if (unit != null) { unit.status = UnitStatus.RESERVED; unit.orderId = order.id; }
        }
    }
    @Override public void refreshAfterCheckoutReservation() {
        syncCatalogIndexes();
        evaluateLowStockNotifications();
    }
    @Override public void stageCheckoutOrder(SalesOrder order) {
        orders.put(order.id, order);
        orderByIdempotencyKey.put(order.idempotencyKey, order.id);
        if (interactionConsent) {
            Set<String> tracked = recommendedCartItems.getOrDefault(order.customerId, Set.of());
            Set<String> inOrder = new LinkedHashSet<>();
            for (OrderLine line : order.lines) if (tracked.contains(line.variantId)) inOrder.add(line.variantId);
            if (!inOrder.isEmpty()) recommendedOrderItems.put(order.id, inOrder);
        }
        orderSequence++;
        audit(signedInName, "Tạo đơn chờ thanh toán", order.number + " · giữ tồn kho trước khi gọi cổng thanh toán.");
    }
    @Override public vn.techzone.application.port.PaymentPort.Authorization authorizePayment(String orderId, long total, String key, PaymentVerdict verdict) {
        return paymentGateway.authorize(orderId, total, key, verdict);
    }
    @Override public void completeCheckoutPayment(SalesOrder order) {
        cart.clear();
        recordInteractionFor(order.customerId, "purchase", order.number);
        recordRecommendationPurchases(order);
        recommendedCartItems.remove(signedInCustomerId);
        audit("Cổng thanh toán mock", "Ủy quyền thành công", "Xác nhận " + order.number + "; mã khóa idempotency đã ghi nhận.");
        notify("CUSTOMER:" + order.customerId, "Đơn hàng đã được xác nhận", order.number + " · biên nhận thanh toán đã sẵn sàng.");
    }
    @Override public void rejectCheckoutPayment(SalesOrder order) {
        releaseReservation(order);
        recommendedOrderItems.remove(order.id);
        order.cancel();
        order.pendingReason = "Cổng thanh toán từ chối giao dịch.";
        audit("Cổng thanh toán mock", "Giao dịch bị từ chối", order.number + " · đã hoàn tồn kho giữ trước đó.");
        notify("CUSTOMER:" + order.customerId, "Thanh toán chưa thành công", order.number + " · tồn kho đã được hoàn lại; bạn có thể đặt hàng lại.");
    }
    @Override public void deferCheckoutPayment(SalesOrder order) {
        order.status = OrderStatus.PENDING_REVIEW;
        order.reconciliationDueAt = LocalDateTime.now().plusMinutes(15);
        order.pendingReason = "Chưa nhận được kết quả cuối cùng từ cổng thanh toán; cần đối soát.";
        cart.clear();
        recommendedCartItems.remove(signedInCustomerId);
        audit("Cổng thanh toán mock", "Kết quả chưa rõ", order.number + " · giữ chỗ tồn kho cho đến khi đối soát.");
        notify("CUSTOMER:" + order.customerId, "Đơn hàng đang chờ đối soát", order.number + " · tồn kho được giữ cho đến khi nhận kết quả cuối cùng.");
        notify("STAFF", "Cần đối soát thanh toán", order.number + " · giao dịch có kết quả chưa rõ.");
    }

    @Override public Payment paymentRecord(SalesOrder order,String reference,String status,LocalDateTime authorizedAt) {
        return new Payment(order.id,order.id,order.paymentMethod,reference,order.idempotencyKey,order.total,"VND",status,
                authorizedAt,null,authorizedAt);
    }

    @Override public CheckoutResult checkoutResult(SalesOrder order,String message,boolean confirmed) {
        return new CheckoutResult(order,message,confirmed);
    }

    public String reconcilePending(String orderId, PaymentVerdict verifiedVerdict) {
        return reconcilePaymentCallback(orderId, "manual-" + orderId, verifiedVerdict,
                verifiedVerdict == PaymentVerdict.AUTHORIZED ? "MOCK-RECONCILED-" + orderId : "");
    }

    public synchronized String reconcilePaymentCallback(String orderId, String callbackId, PaymentVerdict verifiedVerdict, String verificationToken) {
        if (callbackId == null || callbackId.isBlank()) return "Mã callback không được để trống.";
        String eventKey = callbackId.trim();
        if (paymentCallbackIds.contains(eventKey)) return "Callback thanh toán này đã được xử lý trước đó.";
        SalesOrder order = orders.get(orderId);
        if (order == null || order.status != OrderStatus.PENDING_REVIEW) return "Không tìm thấy giao dịch cần đối soát.";
        if (verifiedVerdict == null || verifiedVerdict == PaymentVerdict.UNKNOWN) return "Cổng thanh toán vẫn chưa có trạng thái cuối cùng.";
        if (verifiedVerdict == PaymentVerdict.AUTHORIZED && (verificationToken == null || verificationToken.isBlank()))
            return "Cần mã xác minh từ cổng thanh toán mock trước khi duyệt.";
        boolean verified;
        try { verified = paymentGateway.verifyCallback(order.id, eventKey, verifiedVerdict, verificationToken); }
        catch (RuntimeException ex) { verified = false; }
        if (!verified) return "Callback thanh toán không được cổng mock xác minh; đơn tiếp tục chờ đối soát.";
        paymentCallbackIds.add(eventKey);
        order.reconciliationDueAt = null;
        if (verifiedVerdict == PaymentVerdict.AUTHORIZED) {
            LocalDateTime verifiedAt=LocalDateTime.now();
            try { order.confirm(paymentRecord(order,verificationToken.trim(),"AUTHORIZED",verifiedAt),verifiedAt); }
            catch(IllegalStateException invalidAuthorization) { return "Thanh toán không đủ điều kiện chuyển đơn hàng sang đã xác nhận."; }
            recordInteractionFor(order.customerId, "purchase", order.number);
            recordRecommendationPurchases(order);
            audit("Quản trị viên", "Đối soát thanh toán", order.number + " · xác nhận từ kết quả đã kiểm tra.");
            notify("CUSTOMER:" + order.customerId, "Thanh toán đã được đối soát", order.number + " · đơn hàng đã chuyển sang khâu lấy hàng.");
            return "Đã đối soát duyệt thanh toán và đưa đơn vào hàng chờ lấy.";
        }
        releaseReservation(order);
        recommendedOrderItems.remove(order.id);
        order.cancel();
        order.pendingReason = "Đối soát xác nhận giao dịch bị từ chối.";
        audit("Quản trị viên", "Đối soát thanh toán", order.number + " · từ chối, đã trả tồn kho.");
        notify("CUSTOMER:" + order.customerId, "Kết quả đối soát thanh toán", order.number + " · giao dịch bị từ chối và tồn kho được hoàn lại.");
        return "Đối soát xác nhận từ chối; tồn kho giữ trước đó đã được hoàn lại.";
    }

    public synchronized int escalateOverduePaymentReviews() {
        int count = 0;
        LocalDateTime now = LocalDateTime.now();
        for (SalesOrder order : orders.values()) if (order.status == OrderStatus.PENDING_REVIEW
                && order.reconciliationDueAt != null && !order.reconciliationDueAt.isAfter(now)
                && escalatedPaymentOrders.add(order.id)) {
            count++;
            audit("Hệ thống", "Quá hạn đối soát thanh toán", order.number + " · giữ tồn kho cho tới khi có kết quả đã xác minh.");
            notify("STAFF", "Thanh toán quá hạn đối soát", order.number + " · cần kiểm tra kết quả với cổng thanh toán mock.");
        }
        return count;
    }

    private void releaseReservation(SalesOrder order) {
        for (OrderLine line : order.lines) {
            InventoryBalance b = balance(line.variantId);
            int release = line.reservedQuantity;
            b.release(release);
            for (InventoryUnit unit : unitsBySerial.values()) if (unit.variantId.equals(line.variantId)
                    && unit.status == UnitStatus.RESERVED && order.id.equals(unit.orderId)) {
                unit.status = UnitStatus.AVAILABLE; unit.orderId = null;
            }
            line.reservedQuantity = 0;
        }
        syncCatalogIndexes();
        evaluateLowStockNotifications();
    }

    public SalesOrder order(String orderId) { return orders.get(orderId); }

    public List<SalesOrder> fulfillmentOrders() {
        return orders().stream().filter(o -> o.status == OrderStatus.CONFIRMED || o.status == OrderStatus.PACKED
                || o.status == OrderStatus.SHIPPED || o.status == OrderStatus.DELIVERED || o.status == OrderStatus.PENDING_REVIEW).toList();
    }

    public synchronized String allocateSerial(String orderId, String variantId, String scannedSerial, String staffName) {
        SalesOrder order = orders.get(orderId);
        if (order == null || order.status != OrderStatus.CONFIRMED) return "Chỉ đơn đã xác nhận mới được cấp Serial/IMEI.";
        OrderLine line = order.lines.stream().filter(l -> l.variantId.equals(variantId)).findFirst().orElse(null);
        if (line == null || line.reservedQuantity <= 0) return "Đơn không còn số lượng chờ cấp cho mã sản phẩm này.";
        if (scannedSerial == null || scannedSerial.isBlank()) return "Hãy quét hoặc nhập Serial/IMEI.";
        InventoryUnit unit = unitsBySerial.get(scannedSerial.trim().toUpperCase(Locale.ROOT));
        if (unit == null) return "Không tìm thấy Serial/IMEI trong kho.";
        if (!unit.verifyVariant(variantId)) return "SKU không khớp. Serial thuộc " + product(unit.variantId).name + ".";
        if (unit.status != UnitStatus.RESERVED || !order.id.equals(unit.orderId)) return "Serial/IMEI này không nằm trong số lượng đã giữ cho đơn đang lấy.";
        LocalDateTime scannedAt=LocalDateTime.now();
        vn.techzone.domain.UnitAllocation allocation=new vn.techzone.domain.UnitAllocation(scannedSerial.trim().toUpperCase(Locale.ROOT),
                vn.techzone.domain.UnitAllocation.orderItemKey(order.id,variantId),unit.serial,variantId,"ALLOCATED",staffName,scannedAt);
        if(!allocation.validateAllocation(line,unit,order.id)) return "Tham chiếu cấp phát Serial/IMEI không hợp lệ.";
        unit.status = UnitStatus.ALLOCATED;
        unit.orderId = order.id;
        line.serials.add(unit.serial);
        line.recordAllocation(allocation);
        line.allocateQuantity(1);
        InventoryBalance b = balance(variantId);
        b.allocate(1);
        audit(staffName, "Cấp Serial/IMEI", order.number + " · " + unit.serial + " · " + product(variantId).sku);
        return "Đã xác thực SKU và gắn " + unit.serial + " vào " + order.number + ". Tồn kho chuyển từ giữ chỗ sang đã cấp.";
    }

    public boolean readyToShip(SalesOrder order) {
        return order != null && order.status == OrderStatus.CONFIRMED && order.lines.stream()
                .allMatch(line -> line.allocatedQuantity == line.quantity);
    }

    public synchronized String shipOrder(String orderId, String carrier, String staffName) {
        return shipOrder(orderId, carrier, 1000, "30 x 20 x 10 cm", staffName);
    }

    public synchronized String shipOrder(String orderId, String carrier, int weightGrams, String dimensions, String staffName) {
        SalesOrder order = orders.get(orderId);
        if (!readyToShip(order)) return "Cần cấp đủ Serial/IMEI hợp lệ cho tất cả sản phẩm trước khi bàn giao.";
        if (weightGrams <= 0 || weightGrams > 100_000 || dimensions == null || dimensions.isBlank() || carrier==null || carrier.isBlank())
            return "Hãy nhập trọng lượng và kích thước kiện hàng hợp lệ.";
        String tracking;
        DeliveryPort.DispatchRequest request = new DeliveryPort.DispatchRequest(order.number, carrier, order.customerName,
                order.shippingAddress, weightGrams, dimensions.trim(), Math.max(0, order.subtotal - order.discount));
        try { tracking = deliveryGateway.createTrackingCode(request); }
        catch (RuntimeException ex) { return "Không thể tạo vận đơn từ hãng vận chuyển mock."; }
        if (tracking == null || tracking.isBlank()) return "Không tạo được vận đơn; hãy kiểm tra hãng vận chuyển.";
        try { order.markShipped(tracking,carrier,weightGrams,dimensions,LocalDateTime.now()); }
        catch(IllegalStateException ex) { return "Đơn hàng chưa đủ điều kiện bàn giao vận chuyển."; }
        String pickupEventId = "PICKUP-" + order.trackingCode;
        if (shipmentCallbackIds.add(pickupEventId)) putShipmentEvent(pickupEventId, order, "Đã lấy hàng", "Kho TechZone · TP. Hồ Chí Minh",
                "Đơn vị vận chuyển đã nhận bản kê và kiện hàng.", LocalDateTime.now());
        audit(staffName, "Bàn giao vận chuyển", order.number + " · " + carrier + " · " + order.trackingCode);
        notify("CUSTOMER:" + order.customerId, "Đơn hàng đã bàn giao vận chuyển", order.number + " · " + carrier + " · " + order.trackingCode);
        return "Đã tạo vận đơn " + order.trackingCode + " và bản kê giao hàng điện tử.";
    }

    public synchronized String markDelivered(String orderId, String eventId, String staffName) {
        SalesOrder order = orders.get(orderId);
        if (order == null || order.status != OrderStatus.SHIPPED) return "Chỉ đơn đang giao mới nhận được xác nhận phát hàng.";
        if (eventId == null || eventId.isBlank()) return "Mã sự kiện giao hàng không được để trống.";
        boolean verified;
        try { verified = deliveryGateway.verifyCallback(order.trackingCode, eventId.trim(), "Đã giao thành công"); }
        catch (RuntimeException ex) { verified = false; }
        if (!verified) return "Sự kiện giao hàng chưa được hãng mock xác minh.";
        if (!deliveryEvents.add(eventId.trim())) return "Sự kiện giao hàng này đã được xử lý trước đó.";
        putShipmentEvent(eventId.trim(), order, "Đã giao thành công", order.shippingAddress,
                "Đơn vị vận chuyển xác nhận người nhận đã nhận đủ kiện hàng.", LocalDateTime.now());
        return completeDelivery(order, staffName);
    }

    public synchronized String recordShipmentMilestone(String orderId, String callbackId, String milestone, String location, String staffName) {
        SalesOrder order = orders.get(orderId);
        if (order == null || order.status != OrderStatus.SHIPPED) return "Chỉ đơn đang giao mới nhận cập nhật vận chuyển.";
        if (callbackId == null || callbackId.isBlank()) return "Mã sự kiện vận chuyển không được để trống.";
        if (milestone == null || !shipmentMilestones().contains(milestone)) return "Cột mốc vận chuyển không hợp lệ.";
        boolean verified;
        try { verified = deliveryGateway.verifyCallback(order.trackingCode, callbackId.trim(), milestone); }
        catch (RuntimeException ex) { verified = false; }
        if (!verified) return "Callback vận chuyển chưa được hãng mock xác minh.";
        if (!shipmentCallbackIds.add(callbackId.trim())) return "Callback vận chuyển trùng đã được bỏ qua.";
        String expected = nextShipmentMilestone(orderId);
        if (!milestone.equals(expected)) {
            shipmentCallbackIds.remove(callbackId.trim());
            return "Thứ tự vận chuyển chưa hợp lệ. Cột mốc tiếp theo cần ghi là: " + expected + ".";
        }
        String place = location == null || location.isBlank() ? "Trung tâm vận chuyển · Việt Nam" : location.trim();
        putShipmentEvent(callbackId.trim(), order, milestone, place, "Cập nhật được đồng bộ từ Delivery Provider mock.", LocalDateTime.now());
        audit(staffName, "Đồng bộ cột mốc vận chuyển", order.number + " · " + milestone + " · " + place);
        if ("Đã giao thành công".equals(milestone)) {
            deliveryEvents.add(callbackId.trim());
            return completeDelivery(order, staffName);
        }
        notify("CUSTOMER:" + order.customerId, "Cập nhật hành trình đơn hàng", order.number + " · " + milestone + " · " + place);
        return "Đã ghi nhận cột mốc: " + milestone + ".";
    }

    private String completeDelivery(SalesOrder order, String staffName) {
        try { order.markDelivered(LocalDateTime.now()); }
        catch(IllegalStateException ex) { return "Chỉ vận đơn đang giao mới được xác nhận giao thành công."; }
        boolean warrantyPending = false;
        for (OrderLine line : order.lines) for (String serial : line.serials) {
            ProductVariant product = product(line.variantId);
            if (product.warrantyMonths <= 0) { warrantyPending = true; continue; }
            try { warranties.putIfAbsent(serial,Warranty.activateOnDelivery(order,line,product,serial)); }
            catch(IllegalArgumentException ex) { warrantyPending=true; }
        }
        audit(staffName, "Xác nhận giao thành công", order.number + " · kích hoạt bảo hành khi đã có thời hạn.");
        String note = warrantyPending ? " Một số sản phẩm chưa có thời hạn bảo hành; cần xác minh với cửa hàng."
                : " Bảo hành điện tử được kích hoạt cho các Serial/IMEI đã cấp.";
        notify("CUSTOMER:" + order.customerId, "Đã giao hàng", order.number + " ·" + note);
        return "Đã ghi nhận giao hàng." + note;
    }

    private void putShipmentEvent(String id, SalesOrder order, String milestone, String location, String detail, LocalDateTime time) {
        shipmentCallbackIds.add(id);
        shipmentEvents.computeIfAbsent(order.id, key -> new ArrayList<>())
                .add(new ShipmentEvent(id, order.id, milestone, location, detail, time));
    }

    public InventoryUnit unit(String serial) {
        return serial == null ? null : unitsBySerial.get(serial.trim().toUpperCase(Locale.ROOT));
    }
    public List<InventoryUnit> unitsForVariant(String variantId) {
        return unitsBySerial.values().stream().filter(u -> u.variantId.equals(variantId)).toList();
    }
    public String sampleAvailableSerial(String variantId) {
        InventoryUnit unit = findAvailableUnit(variantId);
        return unit == null ? "" : unit.serial;
    }

    public String sampleReservedSerial(String variantId, String orderId) {
        return unitsBySerial.values().stream().filter(u -> u.variantId.equals(variantId)
                && u.status == UnitStatus.RESERVED && orderId.equals(u.orderId)).map(u -> u.serial).findFirst().orElse("");
    }

    private InventoryUnit findAvailableUnit(String variantId) {
        return unitsBySerial.values().stream().filter(u -> u.variantId.equals(variantId) && u.status == UnitStatus.AVAILABLE).findFirst().orElse(null);
    }

    private static String serialFor(String variantId, int index) {
        String compact = variantId.replaceAll("[^A-Za-z0-9]", "");
        return ("SN-TZ-" + compact + "-" + String.format(Locale.ROOT, "%04d", index)).toUpperCase(Locale.ROOT);
    }

    private String issueNewSerial(String variantId) {
        int number = unitsForVariant(variantId).size() + 1;
        String serial = serialFor(variantId, number);
        while (!issuedSerials.add(serial)) serial = serialFor(variantId, ++number);
        return serial;
    }

    public Warranty warranty(String serial) {
        if (serial == null) return null;
        Warranty warranty = warranties.get(serial.trim().toUpperCase(Locale.ROOT));
        if (warranty != null && warranty.status != WarrantyStatus.EXPIRED && !warranty.isValid(LocalDateTime.now())) {
            warranty.status = WarrantyStatus.EXPIRED; dirty = true;
        }
        return warranty;
    }

    public String createRma(String serial, String customerId, String problem) {
        if (!signedInCustomerId.equals(customerId)) return "Không thể tiếp tục: tài khoản hiện tại không sở hữu giao dịch này.";
        return createRmaForOwner(serial, customerId, problem);
    }

    public String createStaffRma(String serial, String customerId, String problem, Role role) {
        if (role != Role.SUPPORT && role != Role.MANAGER && role != Role.ADMIN) return "Chỉ nhân viên hỗ trợ hoặc quản lý được tiếp nhận thay khách.";
        return createRmaForOwner(serial, customerId, problem);
    }

    private String createRmaForOwner(String serial, String customerId, String problem) {
        Warranty warranty = warranty(serial);
        if (warranty == null) return "Chưa có bảo hành điện tử cho Serial/IMEI này. RMA không được tạo.";
        if (!warranty.customerId.equals(customerId)) return "Không thể tiếp tục: tài khoản hiện tại không sở hữu giao dịch này.";
        if (warranty.status != WarrantyStatus.ACTIVE) return "Bảo hành đã hết hạn. Có thể liên hệ dịch vụ sửa chữa tính phí.";
        if (problem == null || problem.isBlank()) return "Vui lòng mô tả lỗi thiết bị.";
        boolean openExists = rmas.values().stream().anyMatch(r -> r.serial.equals(warranty.serial)
                && !Set.of("Hoàn tất", "Từ chối").contains(r.status));
        if (openExists) return "Serial/IMEI này đã có phiếu hỗ trợ đang mở.";
        String id = "RMA-" + rmaSequence++;
        RmaCase request=new RmaCase(id,warranty.serial,customerId,problem.trim(),LocalDateTime.now());
        if(!request.validateEligibility(warranty,customerId,LocalDateTime.now()))return "Bảo hành không đủ điều kiện tạo RMA.";
        rmas.put(id,request);
        audit(customerId.equals(signedInCustomerId) ? signedInName : "Nhân viên hỗ trợ", "Tạo yêu cầu RMA", id + " · " + warranty.serial);
        notify("STAFF", "Yêu cầu RMA mới", id + " · Serial/IMEI " + warranty.serial + " cần kỹ thuật tiếp nhận.");
        return "Đã tạo phiếu " + id + " để bộ phận kỹ thuật xem xét.";
    }

    public String createCurrentCustomerRma(String serial, String problem) {
        return createRmaForOwner(serial, signedInCustomerId, problem);
    }

    public String requestManualWarrantyVerification(String serial, String problem) {
        String clean = serial == null ? "" : serial.trim().toUpperCase(Locale.ROOT);
        if (clean.isBlank() || problem == null || problem.isBlank()) return "Nhập Serial/IMEI và mô tả sự cố để gửi xác minh.";
        if (warrantyVerifications.values().stream().anyMatch(v -> v.serial.equals(clean)
                && v.customerId.equals(signedInCustomerId) && v.status.equals("Chờ xác minh")))
            return "Đã có yêu cầu xác minh đang chờ cho Serial/IMEI này.";
        String id = "VERIFY-" + String.format(Locale.ROOT, "%04d", verificationSequence++);
        warrantyVerifications.put(id, new WarrantyVerification(id, clean, signedInCustomerId, problem.trim(), LocalDateTime.now()));
        notify("STAFF", "Cần xác minh bảo hành thủ công", id + " · " + clean);
        audit(signedInName, "Yêu cầu xác minh bảo hành", id + " · " + clean);
        return "Đã gửi " + id + " cho kỹ thuật kiểm tra hồ sơ giao hàng và quyền sở hữu.";
    }

    public String reviewWarrantyVerification(String verificationId, Role role, String actor) {
        if (role != Role.SUPPORT && role != Role.MANAGER && role != Role.ADMIN) return "Chỉ kỹ thuật, quản lý hoặc quản trị viên được xác minh.";
        WarrantyVerification request = warrantyVerifications.get(verificationId);
        if (request == null || !request.status.equals("Chờ xác minh")) return "Không tìm thấy yêu cầu đang chờ xác minh.";
        InventoryUnit unit = unit(request.serial);
        SalesOrder order = unit == null || unit.orderId == null ? null : orders.get(unit.orderId);
        if (order == null || unit.status != UnitStatus.ALLOCATED || order.status != OrderStatus.DELIVERED || order.deliveredAt == null
                || !order.customerId.equals(request.customerId)
                || order.lines.stream().noneMatch(line -> line.variantId.equals(unit.variantId) && line.serials.contains(unit.serial))) {
            request.status = "Từ chối"; request.result = "Không xác minh được đơn đã giao và quyền sở hữu.";
        } else {
            ProductVariant p = product(unit.variantId);
            if (p.warrantyMonths <= 0) {
                request.result = "Chưa có thông tin thời hạn bảo hành; cần xác minh với cửa hàng.";
                audit(actor, "Xác minh bảo hành thủ công", request.id + " · " + request.result);
                return request.result;
            }
            LocalDateTime expires = order.deliveredAt.plusMonths(p.warrantyMonths);
            if (!expires.isAfter(LocalDateTime.now())) {
                warranties.putIfAbsent(unit.serial, new Warranty(unit.serial, unit.variantId, order.id, order.customerId,
                        order.deliveredAt, expires, WarrantyStatus.EXPIRED));
                request.status = "Từ chối"; request.result = "Bảo hành đã hết hạn; có thể yêu cầu báo giá sửa chữa tính phí.";
            } else {
                warranties.putIfAbsent(unit.serial, new Warranty(unit.serial, unit.variantId, order.id, order.customerId,
                        order.deliveredAt, expires, WarrantyStatus.ACTIVE));
                int previous = rmas.size();
                String result = createRmaForOwner(unit.serial, request.customerId, request.problem);
                boolean open = rmas.values().stream().anyMatch(r -> r.serial.equals(unit.serial)
                        && !Set.of("Hoàn tất", "Từ chối").contains(r.status));
                request.status = rmas.size() > previous ? "Đã xác minh · tạo RMA"
                        : open ? "Đã xác minh · có RMA đang mở" : "Từ chối";
                request.result = result;
            }
        }
        audit(actor, "Xác minh bảo hành thủ công", request.id + " · " + request.status + " · " + request.result);
        notify("CUSTOMER:" + request.customerId, "Kết quả xác minh bảo hành", request.id + " · " + request.status + ". " + request.result);
        return request.status + ": " + request.result;
    }

    public String requestPaidRepair(String serial, String customerId, String problem) {
        if (!signedInCustomerId.equals(customerId)) return "Không xác minh được quyền sở hữu thiết bị.";
        return requestPaidRepairForOwner(serial, customerId, problem, signedInName);
    }

    public String requestStaffPaidRepair(String serial, String customerId, String problem, Role role, String actor) {
        if (role != Role.SUPPORT && role != Role.MANAGER && role != Role.ADMIN) return "Chỉ nhân viên hỗ trợ hoặc quản lý được tiếp nhận thay khách.";
        return requestPaidRepairForOwner(serial, customerId, problem, actor);
    }

    private String requestPaidRepairForOwner(String serial, String customerId, String problem, String actor) {
        Warranty w = warranty(serial);
        if (w == null || !w.customerId.equals(customerId)) return "Không xác minh được quyền sở hữu thiết bị.";
        if (w.status != WarrantyStatus.EXPIRED) return "Bảo hành còn hiệu lực; hãy tạo yêu cầu RMA thông thường.";
        if (problem == null || problem.isBlank()) return "Vui lòng mô tả sự cố để yêu cầu báo giá.";
        if (rmas.values().stream().anyMatch(r -> r.serial.equals(w.serial) && !Set.of("Hoàn tất", "Từ chối").contains(r.status)))
            return "Thiết bị đã có phiếu hỗ trợ đang mở.";
        String id = "RMA-" + rmaSequence++;
        RmaCase request = new RmaCase(id, w.serial, customerId, problem.trim(), LocalDateTime.now(), "Sửa chữa tính phí");
        request.status = "Chờ báo giá";
        rmas.put(id, request);
        audit(actor, "Yêu cầu báo giá sửa chữa", id + " · " + w.serial);
        notify("STAFF", "Yêu cầu báo giá sửa chữa", id + " · " + w.serial);
        return "Đã tạo " + id + " để kỹ thuật báo giá sửa chữa ngoài bảo hành.";
    }

    public String updateRma(String rmaId, String status, String assignedTo, String diagnosis,
                            String replacementParts, String resolutionNotes, Role actorRole, String actor) {
        RmaCase rma = rmas.get(rmaId);
        if (rma == null) return "Không tìm thấy phiếu RMA.";
        if (actorRole != Role.SUPPORT && actorRole != Role.MANAGER && actorRole != Role.ADMIN)
            return "Chỉ bộ phận hỗ trợ, quản lý hoặc quản trị viên được cập nhật phiếu kỹ thuật.";
        if (Set.of("Hoàn tất", "Từ chối").contains(rma.status)) return "Phiếu đã đóng; cần tạo yêu cầu mới nếu thiết bị phát sinh lỗi khác.";
        Set<String> statuses = Set.of("Chờ kỹ thuật tiếp nhận", "Chờ báo giá", "Chờ khách xác nhận báo giá", "Đang kiểm tra", "Chờ linh kiện", "Đang sửa chữa", "Hoàn tất", "Từ chối");
        if (!statuses.contains(status)) return "Trạng thái xử lý RMA không hợp lệ.";
        String cleanDiagnosis = diagnosis == null ? "" : diagnosis.trim();
        String cleanResolution = resolutionNotes == null ? "" : resolutionNotes.trim();
        if (Set.of("Hoàn tất", "Từ chối").contains(status) && cleanResolution.isBlank())
            return "Hãy ghi kết luận trước khi đóng phiếu RMA.";
        String previous = rma.status;
        if(Set.of("Hoàn tất","Từ chối").contains(status)) {
            try { rma.closeClaim(status,cleanResolution,LocalDateTime.now()); }
            catch(IllegalArgumentException ex) { return "Hãy ghi kết luận trước khi đóng phiếu RMA."; }
        } else { rma.status=status;rma.updatedAt=LocalDateTime.now(); }
        rma.assignedTo = assignedTo == null || assignedTo.isBlank() ? actor : assignedTo.trim();
        rma.diagnosis = cleanDiagnosis;
        rma.replacementParts = replacementParts == null ? "" : replacementParts.trim();
        rma.resolutionNotes = cleanResolution;
        audit(actor, "Cập nhật xử lý RMA", rma.id + " · " + previous + " → " + status
                + (cleanDiagnosis.isBlank() ? "" : " · chẩn đoán: " + cleanDiagnosis)
                + (rma.replacementParts.isBlank() ? "" : " · linh kiện: " + rma.replacementParts)
                + (cleanResolution.isBlank() ? "" : " · kết luận: " + cleanResolution));
        notify("CUSTOMER:" + rma.customerId, "Phiếu hỗ trợ được cập nhật", rma.id + " · trạng thái: " + status + ".");
        return "Đã cập nhật phiếu " + rma.id + " · " + status + ".";
    }

    public String addConsultationNote(String orderId, String note, String actor) {
        SalesOrder order = orders.get(orderId);
        if (order == null) return "Không tìm thấy đơn hàng.";
        if (note == null || note.isBlank()) return "Ghi chú tư vấn không được để trống.";
        ConsultationNote entry = new ConsultationNote("NOTE-" + String.format(Locale.ROOT, "%04d", noteSequence++),
                orderId, actor, note.trim(), LocalDateTime.now());
        consultationNotes.add(0, entry);
        audit(actor, "Ghi chú tư vấn đơn hàng", order.number + " · " + entry.id);
        return "Đã thêm ghi chú tư vấn " + entry.id + ".";
    }

    public String verifySalesOrder(String orderId, Role role, String actor) {
        if (role != Role.SALES && role != Role.MANAGER && role != Role.ADMIN) return "Chỉ bán hàng, quản lý hoặc quản trị viên được xác minh đơn.";
        SalesOrder order = orders.get(orderId);
        if (order == null) return "Không tìm thấy đơn hàng.";
        if (!isPaid(order) || order.paymentReference.isBlank()) return "Chỉ xác minh đơn đã có kết quả thanh toán được duyệt.";
        if (order.shippingAddress == null || order.shippingAddress.isBlank() || order.lines.isEmpty())
            return "Đơn thiếu địa chỉ hoặc sản phẩm; không thể xác minh.";
        if (order.salesVerifiedAt != null) return "Đơn đã được xác minh bởi " + order.salesVerifiedBy + ".";
        order.salesVerifiedBy = actor;
        order.salesVerifiedAt = LocalDateTime.now();
        audit(actor, "Xác minh thông tin đơn hàng", order.number + " · địa chỉ, sản phẩm và kết quả thanh toán đã được đối chiếu.");
        return "Đã xác minh thông tin đơn " + order.number + ".";
    }

    public synchronized String restock(String variantId, int quantity, String actor) {
        return restock(variantId,quantity,actor,"TZR-"+UUID.randomUUID());
    }

    private synchronized String restock(String variantId,int quantity,String actor,String batchNumber) {
        if (!products.containsKey(variantId)) return "Không tìm thấy SKU.";
        if (quantity <= 0 || quantity > 1000) return "Số lượng nhập phải trong khoảng 1–1.000.";
        InventoryBalance b = balance(variantId);
        b.available += quantity;
        LocalDateTime receivedAt=LocalDateTime.now();
        for (int i = 0; i < quantity; i++) {
            String serial = issueNewSerial(variantId);
            unitsBySerial.put(serial,new InventoryUnit(serial,variantId,receivedAt,batchNumber,"UNASSIGNED"));
        }
        syncCatalogIndexes();
        evaluateLowStockNotifications();
        audit(actor, "Nhập kho mock", products.get(variantId).sku + " · +" + quantity + " đơn vị.");
        return "Đã nhập " + quantity + " đơn vị vào " + products.get(variantId).sku + ".";
    }

    public synchronized String adjustInventory(String variantId, int delta, String reason, String actor) {
        ProductVariant product = product(variantId);
        InventoryBalance balance = balance(variantId);
        if (product == null || balance == null) return "Không tìm thấy SKU cần điều chỉnh.";
        if (delta == 0 || delta < -1000 || delta > 1000) return "Điều chỉnh phải nằm trong khoảng ±1–1.000 đơn vị.";
        if (reason == null || reason.isBlank()) return "Hãy nhập lý do kiểm kê hoặc điều chỉnh.";
        if (delta < 0) {
            int removeCount = -delta;
            if (removeCount > balance.available) return "Không thể giảm quá tồn có thể bán; hàng giữ chỗ và đã cấp không được xóa.";
            List<InventoryUnit> removable = unitsBySerial.values().stream().filter(u -> u.variantId.equals(variantId)
                    && u.status == UnitStatus.AVAILABLE).limit(removeCount).toList();
            if (removable.size() != removeCount) return "Số lượng Serial/IMEI sẵn sàng không khớp sổ kho; cần kiểm kê lại.";
            removable.forEach(u -> unitsBySerial.remove(u.serial));
        } else {
            String adjustmentBatch="ADJUSTMENT-"+UUID.randomUUID();
            for (int i = 0; i < delta; i++) {
                String serial = issueNewSerial(variantId);
                unitsBySerial.put(serial, new InventoryUnit(serial,variantId,null,adjustmentBatch,balance.warehouse));
            }
        }
        balance.available += delta;
        syncCatalogIndexes();
        evaluateLowStockNotifications();
        audit(actor, "Điều chỉnh tồn kho", product.sku + " · " + (delta > 0 ? "+" : "") + delta + " · " + reason.trim());
        return "Đã điều chỉnh " + product.sku + " " + (delta > 0 ? "+" : "") + delta + " đơn vị. Số liệu Serial/IMEI và sổ kho đã đồng bộ.";
    }

    private String supplierFor(ProductVariant p) {
        return switch (p.brand) {
            case "Intel" -> "Intel Distribution VN";
            case "AMD" -> "Synnex FPT";
            case "Apple" -> "Apple Việt Nam";
            default -> p.brand + " Authorized Partner";
        };
    }

    public List<SupplierReturn> supplierReturns() { return List.copyOf(supplierReturns); }

    private boolean canManageSupplierReturn(Role role) {
        return role == Role.WAREHOUSE || role == Role.MANAGER || role == Role.ADMIN;
    }

    public synchronized String confirmSupplierReturn(String id, Role role, String actor) {
        if (!canManageSupplierReturn(role)) return "Chỉ kho, quản lý hoặc quản trị viên được bàn giao hàng trả.";
        SupplierReturn request = supplierReturns.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
        if (request == null) return "Không tìm thấy yêu cầu trả nhà cung cấp.";
        if (!"Chờ bàn giao".equals(request.status)) return "Yêu cầu đã được bàn giao trước đó.";
        InventoryUnit unit = unit(request.serial);
        if (unit == null || unit.status != UnitStatus.RETURN_PENDING)
            return "Serial/IMEI không còn ở trạng thái chờ trả; hãy kiểm kê trước khi bàn giao.";
        unitsBySerial.remove(unit.serial);
        request.status = "Đã bàn giao mock";
        audit(actor, "Bàn giao trả nhà cung cấp", request.id + " · " + request.serial + " · " + request.reason);
        return "Đã bàn giao " + request.id + "; tồn có thể bán đã được giảm khi tạo yêu cầu.";
    }

    public synchronized String cancelSupplierReturn(String id, Role role, String actor) {
        if (!canManageSupplierReturn(role)) return "Chỉ kho, quản lý hoặc quản trị viên được hủy yêu cầu trả.";
        SupplierReturn request = supplierReturns.stream().filter(r -> r.id.equals(id)).findFirst().orElse(null);
        if (request == null || !"Chờ bàn giao".equals(request.status)) return "Không tìm thấy yêu cầu đang chờ.";
        InventoryUnit unit = unit(request.serial);
        if (unit == null || unit.status != UnitStatus.RETURN_PENDING) return "Serial/IMEI không còn ở trạng thái chờ trả; hãy kiểm kê.";
        unit.status = UnitStatus.AVAILABLE; balance(request.variantId).available++;
        request.status = "Đã hủy"; syncCatalogIndexes(); evaluateLowStockNotifications();
        audit(actor, "Hủy yêu cầu trả nhà cung cấp", request.id + " · " + request.serial);
        return "Đã hủy " + request.id + " và đưa Serial về tồn có thể bán.";
    }

    public String createRestockRequest(String variantId, int quantity, String actor) {
        ProductVariant p = product(variantId);
        if (p == null || quantity < 1 || quantity > 1000) return "Mã SKU hoặc số lượng không hợp lệ.";
        String supplier = supplierFor(p);
        String id = "PO-" + String.format(Locale.ROOT, "%04d", restockRequests.size() + 1);
        RestockRequest request = new RestockRequest(id, variantId, quantity, supplier, LocalDateTime.now());
        request.leadDays = supplierLeadDays.getOrDefault(variantId, 5);
        request.supplierReference = "SUP-" + p.brand.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + "-" + id.substring(3);
        restockRequests.add(0, request);
        audit(actor, "Tạo yêu cầu nhập hàng", id + " · " + p.sku + " · " + quantity + " đơn vị tới " + supplier + ".");
        return "Đã tạo đề nghị " + id + " gửi " + supplier + " · dự kiến " + request.leadDays + " ngày. Tồn kho chỉ tăng khi nhận hàng.";
    }

    public int supplierLeadDays(String variantId) { return supplierLeadDays.getOrDefault(variantId, 5); }

    public String syncSupplierCatalog(String variantId, String specKey, String specValue, int leadDays, String actor) {
        ProductVariant product = product(variantId);
        if (product == null) return "Không tìm thấy mã hàng nhà cung cấp.";
        if (leadDays < 1 || leadDays > 90) return "Thời gian cung ứng phải trong khoảng 1–90 ngày.";
        String result = updateTechnicalSpec(variantId, specKey, specValue, actor);
        if (!result.startsWith("Đã cập nhật")) return result;
        supplierLeadDays.put(variantId, leadDays);
        syncCatalogIndexes();
        audit(actor, "Đồng bộ dữ liệu nhà cung cấp", product.sku + " · thông số " + specKey + " · lead time " + leadDays + " ngày.");
        return "Đã đồng bộ thông số và thời gian cung ứng cho " + product.sku + ". Cặp tương thích liên quan được yêu cầu rà soát nếu thông số thay đổi.";
    }

    public String receiveRestockRequest(String requestId, String actor) {
        RestockRequest request = restockRequests.stream().filter(r -> r.id.equals(requestId)).findFirst().orElse(null);
        if (request == null) return "Không tìm thấy đề nghị nhập hàng.";
        if (!"Chờ nhà cung cấp xác nhận".equals(request.status)) return "Đề nghị này đã được nhận trước đó.";
        String result = restock(request.variantId,request.quantity,actor,"TZ-"+request.id);
        request.status = "Đã nhận hàng vào kho";
        audit(actor, "Nhận hàng từ nhà cung cấp", request.id + " · " + request.quantity + " đơn vị.");
        return result;
    }

    private boolean canReviewCompatibility(Role role) {
        return role == Role.SUPPORT || role == Role.WAREHOUSE || role == Role.MANAGER || role == Role.ADMIN;
    }

    public String flagCompatibility(String primaryId, String accessoryId, String reason, Role role, String actor) {
        if (!canReviewCompatibility(role)) return "Vai trò hiện tại không được rà soát tương thích.";
        ProductVariant primary = product(primaryId), accessory = product(accessoryId);
        if (primary == null || accessory == null || primaryId.equals(accessoryId)) return "Chọn hai sản phẩm hợp lệ để rà soát.";
        String key = pairKey(primaryId, accessoryId);
        approvedPairs.remove(key); // A reported unsafe pairing is hidden immediately while staff review it.
        boolean alreadyOpen = compatibilityReviews.stream().anyMatch(r -> pairKey(r.primaryVariantId, r.accessoryVariantId).equals(key)
                && "Chờ rà soát".equals(r.status));
        if (!alreadyOpen) compatibilityReviews.add(0, new CompatibilityReview("CMP-" + (compatibilityReviews.size() + 15),
                primaryId, accessoryId, reason == null || reason.isBlank() ? "Cần xác minh thông số tương thích." : reason.trim(), LocalDateTime.now()));
        syncCatalogIndexes();
        audit(actor, "Gắn cờ quy tắc tương thích", primary.sku + " ↔ " + accessory.sku + " · tạm ẩn cặp trong gợi ý.");
        return "Đã ẩn cặp linh kiện và chuyển sang hàng chờ rà soát kỹ thuật.";
    }

    public String reviewCompatibility(String reviewId, boolean approve, Role role, String actor, String note) {
        if (!canReviewCompatibility(role)) return "Vai trò hiện tại không được rà soát tương thích.";
        CompatibilityReview review = compatibilityReviews.stream().filter(r -> r.id.equals(reviewId)).findFirst().orElse(null);
        if (review == null || !"Chờ rà soát".equals(review.status)) return "Yêu cầu đã được xem xét hoặc không tồn tại.";
        PairCompatibility.Result checked=PairCompatibility.assess(product(review.primaryVariantId),product(review.accessoryVariantId));
        if (approve && checked.status()!=PairCompatibility.Status.MATCH) {
            if(checked.status()==PairCompatibility.Status.CONFLICT) {
                review.status="Đã chặn · dữ liệu không đạt";review.reviewedBy=actor;review.reviewNote=checked.detail();review.reviewedAt=LocalDateTime.now();
                audit(actor,"Chặn cặp không tương thích",review.id+" · "+review.reviewNote);
                return "Không thể phê duyệt: "+checked.detail();
            }
            return "Không thể phê duyệt khi thiếu thông số hoặc quy tắc kiểm tra: "+checked.detail()+" Yêu cầu vẫn chờ rà soát.";
        }
        if (approve && (note == null || note.isBlank())) return "Nhập căn cứ xác minh trước khi duyệt cặp tương thích.";
        review.status = approve ? "Đã xác minh · cho phép" : "Đã chặn · không tương thích";
        review.reviewedBy = actor;
        review.reviewedAt = LocalDateTime.now();
        review.reviewNote = note == null || note.isBlank() ? "Cặp không an toàn; giữ trạng thái ẩn." : note.trim();
        if (approve) approvedPairs.add(pairKey(review.primaryVariantId, review.accessoryVariantId));
        syncCatalogIndexes();
        audit(actor, approve ? "Phê duyệt cặp tương thích" : "Từ chối cặp tương thích", review.id + " · " + review.reviewNote);
        return approve ? "Đã cập nhật cặp tương thích sau khi xác minh." : "Đã ghi nhận cặp không tương thích; hệ thống tiếp tục ẩn gợi ý.";
    }

    private boolean safePair(String leftId, String rightId) {
        ProductVariant a = product(leftId), b = product(rightId);
        if (a == null || b == null) return false;
        ProductVariant cpu = "CPU".equals(a.category) ? a : "CPU".equals(b.category) ? b : null;
        ProductVariant board = "Mainboard".equals(a.category) ? a : "Mainboard".equals(b.category) ? b : null;
        if (cpu != null && board != null) return !cpu.spec("socket").isBlank() && cpu.spec("socket").equals(board.spec("socket"));
        ProductVariant ram = "RAM".equals(a.category) ? a : "RAM".equals(b.category) ? b : null;
        if (ram != null && board != null) return !ram.spec("memoryType").isBlank() && ram.spec("memoryType").equals(board.spec("memoryType"));
        ProductVariant gpu = "GPU".equals(a.category) ? a : "GPU".equals(b.category) ? b : null;
        ProductVariant psu = "PSU".equals(a.category) ? a : "PSU".equals(b.category) ? b : null;
        if (gpu != null && psu != null) {
            try {
                int min = Integer.parseInt(gpu.spec("minPsu").replaceAll("[^0-9]", ""));
                int wattage = Integer.parseInt(psu.spec("wattage").replaceAll("[^0-9]", ""));
                return min > 0 && wattage >= min;
            } catch (NumberFormatException ex) { return false; }
        }
        ProductVariant phone = "Điện thoại".equals(a.category) ? a : "Điện thoại".equals(b.category) ? b : null;
        ProductVariant accessory = "Phụ kiện".equals(a.category) ? a : "Phụ kiện".equals(b.category) ? b : null;
        if (phone != null && accessory != null) return !phone.spec("model").isBlank()
                && !accessory.spec("forModel").isBlank() && phone.spec("model").equals(accessory.spec("forModel"));
        ProductVariant cooler = "Tản nhiệt".equals(a.category) ? a : "Tản nhiệt".equals(b.category) ? b : null;
        if (cpu != null && cooler != null) return !cpu.spec("socket").isBlank() && !cooler.spec("socketSupport").isBlank()
                && java.util.Arrays.stream(cooler.spec("socketSupport").split("[,;/\\s]+"))
                .anyMatch(socket -> socket.equalsIgnoreCase(cpu.spec("socket").trim()));
        ProductVariant ssd = "SSD".equals(a.category) ? a : "SSD".equals(b.category) ? b : null;
        if (board != null && ssd != null) {
            String slots=normalize(board.spec("m2"));
            Matcher count=Pattern.compile("(?:^|\\s)([1-9][0-9]*)\\s*(?:khe|slots?)\\b").matcher(slots);
            return !Pattern.compile("\\b(khong|none|not)\\b").matcher(slots).find()
                    && count.find() && slots.contains("m.2") && ssd.spec("interface").toUpperCase(Locale.ROOT).contains("M.2");
        }
        return false;
    }

    public void setThreshold(String variantId, int threshold, String actor) {
        if (balance(variantId) == null) return;
        balance(variantId).reorderThreshold = Math.max(0, threshold);
        evaluateLowStockNotifications();
        audit(actor, "Cập nhật ngưỡng nhập lại", products.get(variantId).sku + " · " + threshold + " đơn vị.");
    }

    public List<ProductVariant> lowStockProducts() {
        return products.values().stream().filter(p -> balance(p.id).available <= balance(p.id).reorderThreshold).toList();
    }

    public List<SalesOrder> ordersForCustomer(String customerId) {
        return orders().stream().filter(o -> o.customerId.equals(customerId)).toList();
    }

    public String addReview(String productId, String reviewText, int stars) {
        ProductVariant product = product(productId);
        if (product == null || reviewText == null || reviewText.isBlank()) return "Hãy chọn sản phẩm và nhập nhận xét.";
        if (stars < 1 || stars > 5) return "Số sao phải từ 1 đến 5.";
        boolean purchased = orders.values().stream().anyMatch(o -> o.customerId.equals(signedInCustomerId)
                && Set.of(OrderStatus.CONFIRMED, OrderStatus.PACKED, OrderStatus.SHIPPED, OrderStatus.DELIVERED).contains(o.status)
                && o.lines.stream().anyMatch(l -> l.variantId.equals(productId)));
        if (!purchased) return "Chỉ tài khoản đã mua sản phẩm mới gửi được đánh giá đã xác minh.";
        reviews.add(0, new String[]{signedInName, product.name, reviewText.trim(), Integer.toString(stars), "Đã xác minh"});
        recordInteraction("review", product.name,product.id);
        audit(signedInName, "Gửi đánh giá", product.name + " · " + stars + " sao.");
        return "Đã nhận đánh giá đã xác minh.";
    }

    private void recordInteraction(String type, String detail) {
        recordInteractionFor(signedInCustomerId, type, detail);
    }

    private void recordInteraction(String type,String detail,String variantId) {
        recordInteractionFor(signedInCustomerId,type,detail,variantId);
    }

    private void recordInteractionFor(String customerId, String type, String detail) {
        recordInteractionFor(customerId,type,detail,null);
    }

    private void recordInteractionFor(String customerId,String type,String detail,String variantId) {
        recordStructuredInteraction(customerId,type,detail,variantId,UUID.randomUUID().toString(),null,null,null,null);
    }

    private void recordStructuredInteraction(String type,String detail,String variantId,String requestId,String query,
                                             String filters,String sortBy,Integer page) {
        recordStructuredInteraction(signedInCustomerId,type,detail,variantId,requestId,query,filters,sortBy,page);
    }

    private void recordStructuredInteraction(String customerId,String type,String detail,String variantId,String requestId,
                                             String query,String filters,String sortBy,Integer page) {
        String safeRequestId=requestId==null || requestId.isBlank()?UUID.randomUUID().toString():requestId;
        String sessionKey=customerId==null?"SESSION:"+safeRequestId:"CUSTOMER:"+customerId;
        String context="customer="+customerId+" · "+detail;
        InteractionEvent event=new InteractionEvent(type,context,LocalDateTime.now(),customerId,variantId,sessionKey,safeRequestId,
                "WEB",customerId==null?"UNKNOWN":"PERSONALIZATION",Objects.toString(query,""),Objects.toString(filters,"{}"),
                Objects.toString(sortBy,"RELEVANCE"),page==null?1:page,context);
        if (event.validateConsent(consentByCustomer.getOrDefault(customerId,false))) {
            interactionEvents.add(event);
            dirty = true;
        }
    }

    private void recordRecommendationPurchases(SalesOrder order) {
        Set<String> items = recommendedOrderItems.remove(order.id);
        if (items != null) for (String variantId : items)
            recordInteractionFor(order.customerId, "recommendation_purchase", order.number + " · " + variantId,variantId);
        rebuildAiModel();
    }

    private void evaluateLowStockNotifications() {
        for (ProductVariant p : products.values()) {
            String key = "LOW-STOCK-" + p.id;
            InventoryBalance b = balance(p.id);
            if (b.available <= b.reorderThreshold) {
                if (lowStockNotified.add(key)) notify("STAFF", "Cảnh báo tồn kho thấp", p.sku + " · còn " + b.available + " / ngưỡng " + b.reorderThreshold + ".");
            } else lowStockNotified.remove(key);
        }
    }

    private void notify(String audience, String title, String message) {
        notifications.add(0, new AppNotification("NTF-" + String.format(Locale.ROOT, "%04d", notificationSequence++),
                audience, title, message, LocalDateTime.now()));
        if (notifications.size() > 150) notifications.remove(notifications.size() - 1);
        dirty = true;
    }

    private void audit(String actor, String action, String detail) { auditEntries.add(0, new AuditEntry(actor, action, detail)); dirty = true; }

    private boolean isPaid(SalesOrder order) {
        return Set.of(OrderStatus.CONFIRMED, OrderStatus.PACKED, OrderStatus.SHIPPED, OrderStatus.DELIVERED).contains(order.status);
    }

    public List<SalesOrder> paidOrdersThisMonth() {
        YearMonth month = YearMonth.now();
        return orders.values().stream().filter(this::isPaid)
                .filter(o -> o.paidAt != null && YearMonth.from(o.paidAt).equals(month)).toList();
    }

    /** Sales after discounts, excluding VAT and shipping, from orders currently paid in this session. */
    public long revenueThisMonth() {
        return paidOrdersThisMonth().stream().mapToLong(o -> o.subtotal - o.discount).sum();
    }

    public long grossSalesThisMonth() { return paidOrdersThisMonth().stream().mapToLong(o -> o.subtotal).sum(); }
    public long averageOrderThisMonth() {
        List<SalesOrder> paid = paidOrdersThisMonth();
        return paid.isEmpty() ? 0 : paid.stream().mapToLong(o -> o.subtotal - o.discount).sum() / paid.size();
    }
    public long completedOrdersThisMonth() {
        YearMonth month = YearMonth.now();
        return orders.values().stream().filter(o -> o.status == OrderStatus.DELIVERED && o.deliveredAt != null
                && YearMonth.from(o.deliveredAt).equals(month)).count();
    }
    public long[] dailySales(int days) {
        int count = Math.max(1, days);
        long[] totals = new long[count];
        LocalDate first = LocalDate.now().minusDays(count - 1L);
        for (SalesOrder order : orders.values()) if (isPaid(order) && order.paidAt != null) {
            long index = java.time.temporal.ChronoUnit.DAYS.between(first, order.paidAt.toLocalDate());
            if (index >= 0 && index < count) totals[(int) index] += order.subtotal - order.discount;
        }
        return totals;
    }

    public record CategoryReport(String category, long grossSales, int units, int available, double sessionTurnover) { }

    public List<CategoryReport> categoryReports() {
        Map<String, long[]> amounts = new LinkedHashMap<>();
        for (ProductVariant product : products.values()) {
            long[] row = amounts.computeIfAbsent(product.category, key -> new long[4]);
            InventoryBalance b = balance(product.id);
            row[2] += b.available;
            row[3] += openingSellable.getOrDefault(product.id, 0) + b.available + b.reserved;
        }
        for (SalesOrder order : paidOrdersThisMonth()) for (OrderLine line : order.lines) {
            long[] row = amounts.get(product(line.variantId).category);
            row[0] += line.lineTotal(); row[1] += line.quantity;
        }
        Map<String, Integer> delivered = new HashMap<>();
        for (SalesOrder order : orders.values()) if (order.status == OrderStatus.DELIVERED && order.deliveredAt != null
                && !order.deliveredAt.isBefore(sessionStartedAt)) for (OrderLine line : order.lines)
            delivered.merge(product(line.variantId).category, line.quantity, Integer::sum);
        return amounts.entrySet().stream().map(entry -> {
            long[] row = entry.getValue();
            double averageStock = row[3] / 2.0;
            double turnover = averageStock == 0 ? 0 : delivered.getOrDefault(entry.getKey(), 0) / averageStock;
            return new CategoryReport(entry.getKey(), row[0], (int) row[1], (int) row[2], turnover);
        }).toList();
    }

    public double sessionTurnover() {
        double averageStock = products.values().stream().mapToDouble(p ->
                (openingSellable.getOrDefault(p.id, 0) + balance(p.id).available + balance(p.id).reserved) / 2.0).sum();
        long delivered = orders.values().stream().filter(o -> o.status == OrderStatus.DELIVERED && o.deliveredAt != null
                && !o.deliveredAt.isBefore(sessionStartedAt)).flatMap(o -> o.lines.stream()).mapToLong(l -> l.quantity).sum();
        return averageStock == 0 ? 0 : delivered / averageStock;
    }

    public int totalAvailable() { return balances.values().stream().mapToInt(b -> b.available).sum(); }
    public int pendingPickCount() { return (int) orders.values().stream().filter(o -> o.status == OrderStatus.CONFIRMED).count(); }
    public int pendingReviewCount() { return (int) orders.values().stream().filter(o -> o.status == OrderStatus.PENDING_REVIEW).count(); }
    public int activeWarrantyCount() { return (int) warranties.values().stream().filter(w -> w.status == WarrantyStatus.ACTIVE).count(); }
    public List<SalesOrder> recentOrders(int limit) { return orders().stream().limit(limit).toList(); }

    public static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).trim();
    }

    public static String money(long amount) {
        return String.format(Locale.forLanguageTag("vi-VN"), "%,d ₫", amount).replace(',', '.');
    }

    public static String localizedStatus(OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> "Chờ thanh toán";
            case PENDING_REVIEW -> "Chờ đối soát";
            case CONFIRMED -> "Chờ lấy hàng";
            case PACKED -> "Đã đóng gói";
            case SHIPPED -> "Đang giao";
            case DELIVERED -> "Đã giao";
            case CANCELLED -> "Đã hủy";
        };
    }

    public static String localizedCategory(String category) {
        return switch (category) {
            case "CPU" -> "Bộ xử lý";
            case "Mainboard" -> "Bo mạch chủ";
            case "RAM" -> "Bộ nhớ RAM";
            case "GPU" -> "Card đồ họa";
            case "PSU" -> "Nguồn máy tính";
            case "SSD" -> "Ổ cứng SSD";
            case "Màn hình" -> "Màn hình";
            case "Điện thoại" -> "Điện thoại";
            case "Phụ kiện" -> "Phụ kiện";
            case "Tản nhiệt" -> "Tản nhiệt";
            default -> category;
        };
    }
}
