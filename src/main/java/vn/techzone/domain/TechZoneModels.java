package vn.techzone.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core domain objects used by the in-memory demonstration. */
public final class TechZoneModels {
    private TechZoneModels() { }

    public enum OrderStatus {
        PENDING_PAYMENT, PENDING_REVIEW, CONFIRMED, PACKED, SHIPPED, DELIVERED, CANCELLED
    }

    public enum PaymentVerdict { AUTHORIZED, DECLINED, UNKNOWN }
    public enum UnitStatus { AVAILABLE, RESERVED, ALLOCATED, RETURN_PENDING }
    public enum WarrantyStatus { ACTIVE, EXPIRED }
    public enum Role { CUSTOMER, SALES, WAREHOUSE, SUPPORT, MANAGER, ADMIN }

    public static final class ProductVariant implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        /** Product family ID; legacy snapshots without this field use the variant ID. */
        private final String productId;
        private final String productName;
        // Null in older serialized snapshots; keep separate parent/variant flags after relational reload.
        private final Boolean storedProductActive;
        private final Boolean storedVariantActive;
        private final Boolean originalEffectiveActive;
        public final String sku;
        public final String name;
        public final String category;
        public final String brand;
        public final String description;
        public final String color;
        public long price;
        public long salePrice;
        public int warrantyMonths;
        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;
        public final Map<String, String> specs;
        public boolean active = true;

        public ProductVariant(String id, String sku, String name, String category, String brand,
                              String description, String color, long price, long salePrice,
                              int warrantyMonths, Map<String, String> specs) {
            this(id,id,name,sku,name,category,brand,description,color,price,salePrice,warrantyMonths,specs);
        }

        public ProductVariant(String id,String productId,String productName,String sku,String name,String category,String brand,
                              String description,String color,long price,long salePrice,int warrantyMonths,Map<String,String> specs) {
            this(id,productId,productName,sku,name,category,brand,description,color,price,salePrice,warrantyMonths,specs,true,true);
        }

        public ProductVariant(String id,String productId,String productName,String sku,String name,String category,String brand,
                              String description,String color,long price,long salePrice,int warrantyMonths,Map<String,String> specs,
                              boolean productActive,boolean variantActive) {
            this.id = id;
            this.productId = productId;
            this.productName = productName;
            this.storedProductActive=productActive;
            this.storedVariantActive=variantActive;
            this.originalEffectiveActive=productActive && variantActive;
            this.sku = sku;
            this.name = name;
            this.category = category;
            this.brand = brand;
            this.description = description;
            this.color = color;
            this.price = price;
            this.salePrice = salePrice;
            this.warrantyMonths = warrantyMonths;
            this.createdAt=LocalDateTime.now();this.updatedAt=this.createdAt;
            this.specs = new LinkedHashMap<>(specs);
            this.active=productActive && variantActive;
        }

        public long effectivePrice() { return salePrice > 0 && salePrice < price ? salePrice : price; }
        public long getEffectivePrice() { return effectivePrice(); }
        public String productId() { return productId==null || productId.isBlank()?id:productId; }
        public String productName() { return productName==null || productName.isBlank()?name:productName; }
        public boolean productActiveForStorage() {
            return storedProductActive==null || storedProductActive || (active && !Boolean.TRUE.equals(originalEffectiveActive));
        }
        public boolean variantActiveForStorage() {
            return storedVariantActive==null || originalEffectiveActive==null || active!=originalEffectiveActive ? active : storedVariantActive;
        }
        public String spec(String key) { return specs.getOrDefault(key, ""); }
        public TechnicalSpec technicalSpec(String key) { return new TechnicalSpec(key,spec(key),spec("source."+key),Boolean.parseBoolean(spec("verified."+key))); }
        public boolean matchesFilters(Map<String,String> filters) {
            if(filters==null || filters.isEmpty()) return true;
            for(var filter:filters.entrySet()) {
                String expected=fold(filter.getValue());if(expected.isBlank())continue;
                String actual=specs.entrySet().stream().filter(e->fold(e.getKey()).equals(fold(filter.getKey())))
                        .map(Map.Entry::getValue).findFirst().map(value->fold(value)).orElse("");
                boolean match=expected.matches("\\d+(?:[.,]\\d+)?")
                        ? java.util.regex.Pattern.compile("(?<!\\d)"+java.util.regex.Pattern.quote(expected)+"(?!\\d)").matcher(actual).find()
                        : actual.contains(expected);
                if(actual.isBlank() || !match)return false;
            }
            return true;
        }
        private static String fold(String value) {
            if(value==null)return "";
            String n=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","");
            return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}.,]+"," ").trim();
        }
    }

    public static final class InventoryBalance implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String variantId;
        public int available;
        public int reserved;
        public int allocated;
        public int reorderThreshold;
        public final String warehouse;

        public InventoryBalance(String variantId, int available, int reserved, int allocated,
                                int reorderThreshold, String warehouse) {
            this.variantId = variantId;
            this.available = available;
            this.reserved = reserved;
            this.allocated = allocated;
            this.reorderThreshold = reorderThreshold;
            this.warehouse = warehouse;
        }

        public void reserve(int quantity) {
            if (quantity <= 0 || quantity > available || reserved > Integer.MAX_VALUE - quantity)
                throw new IllegalArgumentException("Invalid inventory reservation");
            available -= quantity;
            reserved += quantity;
        }

        public void allocate(int quantity) {
            if (quantity <= 0 || quantity > reserved || allocated > Integer.MAX_VALUE - quantity)
                throw new IllegalArgumentException("Invalid inventory allocation");
            reserved -= quantity;
            allocated += quantity;
        }

        public void release(int quantity) {
            if (quantity < 0 || quantity > reserved || available > Integer.MAX_VALUE - quantity)
                throw new IllegalArgumentException("Invalid inventory release");
            reserved -= quantity;
            available += quantity;
        }
    }

    public static final class InventoryUnit implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String serial;
        public final String variantId;
        public UnitStatus status = UnitStatus.AVAILABLE;
        public String orderId;
        /** Receipt metadata is null for legacy stock when its origin cannot be verified. */
        public LocalDateTime receivedAt;
        public String batchNumber;
        public String warehouseLocation;
        public InventoryUnit(String serial, String variantId) {
            this(serial,variantId,null,null,null);
        }
        public InventoryUnit(String serial,String variantId,LocalDateTime receivedAt,String batchNumber,String warehouseLocation) {
            this.serial = serial;
            this.variantId = variantId;
            this.receivedAt=receivedAt;this.batchNumber=batchNumber;this.warehouseLocation=warehouseLocation;
        }
        public boolean verifyVariant(String expectedVariantId) { return variantId.equals(expectedVariantId); }
    }

    public static final class CartItem implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String variantId;
        public int quantity;
        /** Zero means a legacy cart has no recorded price snapshot. */
        public long unitPriceSnapshot;
        public java.time.LocalDateTime addedAt;
        public java.time.LocalDateTime updatedAt;
        public CartItem(String variantId, int quantity) { this(variantId,quantity,0,java.time.LocalDateTime.now(),java.time.LocalDateTime.now()); }
        public CartItem(String variantId,int quantity,long unitPriceSnapshot) {
            this(variantId,quantity,unitPriceSnapshot,java.time.LocalDateTime.now(),java.time.LocalDateTime.now());
        }
        public CartItem(String variantId,int quantity,long unitPriceSnapshot,java.time.LocalDateTime addedAt,java.time.LocalDateTime updatedAt) {
            this.variantId=variantId;this.quantity=quantity;this.unitPriceSnapshot=Math.max(0,unitPriceSnapshot);
            this.addedAt=addedAt;this.updatedAt=updatedAt;
        }
        public void updateQuantity(int value) {
            if (value <= 0) throw new IllegalArgumentException("Cart quantity must be positive");
            quantity = value;updatedAt=java.time.LocalDateTime.now();
        }
        public long calculateLineTotal(long price) { return Math.multiplyExact(price, quantity); }
        public long effectiveSnapshot(long currentPrice) { return unitPriceSnapshot>0?unitPriceSnapshot:currentPrice; }
    }

    /** Design-level order item contract; OrderLine preserves the serialized prototype name. */
    public abstract static class OrderItem implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public abstract String variantId();
        public abstract long lineTotal();
        public abstract void reserveQuantity(int count);
        public abstract void allocateQuantity(int count);
    }

    public static final class OrderLine extends OrderItem implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String variantId;
        public final int quantity;
        public int reservedQuantity;
        public int allocatedQuantity;
        public final long unitPrice;
        public long discountAmount;
        public final List<String> serials = new ArrayList<>();
        // Null in snapshots created before allocation evidence was introduced.
        private Map<String, UnitAllocation> allocationDetails;

        public OrderLine(String variantId, int quantity, long unitPrice) {
            this.variantId = variantId;
            this.quantity = quantity;
            this.reservedQuantity = 0;
            this.unitPrice = unitPrice;
        }
        @Override public String variantId() { return variantId; }
        @Override public long lineTotal() { return Math.subtractExact(Math.multiplyExact(unitPrice,quantity),discountAmount); }
        @Override public void reserveQuantity(int count) {
            if(count<=0 || reservedQuantity+allocatedQuantity+count>quantity)
                throw new IllegalArgumentException("Invalid order reservation");
            reservedQuantity+=count;
        }
        @Override public void allocateQuantity(int count) {
            if (count <= 0 || count > reservedQuantity || allocatedQuantity + count > quantity)
                throw new IllegalArgumentException("Invalid order allocation");
            reservedQuantity -= count;
            allocatedQuantity += count;
        }
        public void recordAllocation(UnitAllocation allocation) {
            if (allocationDetails == null) allocationDetails = new LinkedHashMap<>();
            allocationDetails.put(allocation.unitId(),allocation);
        }
        public UnitAllocation allocationDetails(String serial) {
            return allocationDetails == null ? null : allocationDetails.get(serial);
        }
    }

    public static final class SalesOrder implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String number;
        public final String customerId;
        public final String customerName;
        public final String shippingAddress;
        public final String paymentMethod;
        public final String idempotencyKey;
        public final String promotionCode;
        public final LocalDateTime createdAt;
        public final List<OrderLine> lines = new ArrayList<>();
        public OrderStatus status;
        public long subtotal;
        public long discount;
        public long tax;
        public long shippingFee;
        public long total;
        public String paymentReference = "";
        public String trackingCode = "";
        public String carrier = "";
        public int packageWeightGrams;
        public String packageDimensions = "";
        public String salesVerifiedBy = "";
        public LocalDateTime salesVerifiedAt;
        public String pendingReason = "";
        public LocalDateTime paidAt;
        public LocalDateTime shippedAt;
        public LocalDateTime deliveredAt;
        public LocalDateTime reconciliationDueAt;

        public SalesOrder(String id, String number, String customerId, String customerName,
                          String shippingAddress, String paymentMethod, String idempotencyKey,
                          String promotionCode, LocalDateTime createdAt) {
            this.id = id;
            this.number = number;
            this.customerId = customerId;
            this.customerName = customerName;
            this.shippingAddress = shippingAddress;
            this.paymentMethod = paymentMethod;
            this.idempotencyKey = idempotencyKey;
            this.promotionCode = promotionCode;
            this.createdAt = createdAt;
        }
        public void confirm(Payment payment,LocalDateTime confirmedAt) {
            if(payment==null || !payment.orderId().equals(id) || payment.amountMinor()!=total
                    || !payment.verifyAuthorization() || confirmedAt==null
                    || status!=OrderStatus.PENDING_PAYMENT && status!=OrderStatus.PENDING_REVIEW)
                throw new IllegalStateException("Order cannot be confirmed without verified payment authorization");
            status=OrderStatus.CONFIRMED;paymentReference=payment.providerReference();paidAt=confirmedAt;
            reconciliationDueAt=null;pendingReason="";
        }
        public void cancel() {
            if(status!=OrderStatus.PENDING_PAYMENT && status!=OrderStatus.PENDING_REVIEW)
                throw new IllegalStateException("Only an unpaid or unresolved order may be cancelled");
            status=OrderStatus.CANCELLED;
        }
        public void markShipped(String tracking,String carrierName,int weightGrams,String dimensions,LocalDateTime shippedAt) {
            if(status!=OrderStatus.CONFIRMED || lines.isEmpty() || lines.stream().anyMatch(line->line.allocatedQuantity!=line.quantity)
                    || tracking==null || tracking.isBlank() || carrierName==null || carrierName.isBlank()
                    || weightGrams<0 || shippedAt==null)
                throw new IllegalStateException("Shipment requires a paid, fully allocated order and tracking data");
            trackingCode=tracking.trim();carrier=carrierName.trim();packageWeightGrams=weightGrams;
            packageDimensions=dimensions==null?"":dimensions.trim();this.shippedAt=shippedAt;status=OrderStatus.SHIPPED;
        }
        public void markDelivered(LocalDateTime deliveredAt) {
            if(status!=OrderStatus.SHIPPED || deliveredAt==null)throw new IllegalStateException("Only a shipped order can be delivered");
            status=OrderStatus.DELIVERED;this.deliveredAt=deliveredAt;
        }
    }

    public static final class Warranty implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String serial;
        public final String variantId;
        public final String orderId;
        public final String customerId;
        public final LocalDateTime activatedAt;
        public final LocalDateTime expiresAt;
        public WarrantyStatus status;
        public Warranty(String serial, String variantId, String orderId, String customerId,
                        LocalDateTime activatedAt, LocalDateTime expiresAt, WarrantyStatus status) {
            this.serial = serial;
            this.variantId = variantId;
            this.orderId = orderId;
            this.customerId = customerId;
            this.activatedAt = activatedAt;
            this.expiresAt = expiresAt;
            this.status = status;
        }
        public boolean isValid(LocalDateTime at) { return status==WarrantyStatus.ACTIVE && expiresAt!=null && at!=null && expiresAt.isAfter(at); }
        public static Warranty activateOnDelivery(SalesOrder order,OrderLine line,ProductVariant variant,String serial) {
            if(order==null || line==null || variant==null || order.status!=OrderStatus.DELIVERED || order.deliveredAt==null
                    || !line.variantId.equals(variant.id) || !line.serials.contains(serial) || variant.warrantyMonths<=0)
                throw new IllegalArgumentException("Warranty requires a delivered and allocated matching unit");
            return new Warranty(serial,variant.id,order.id,order.customerId,order.deliveredAt,
                    order.deliveredAt.plusMonths(variant.warrantyMonths),WarrantyStatus.ACTIVE);
        }
    }

    /** Design-level RMA request behavior; RmaCase retains legacy storage naming. */
    public abstract static class RMARequest implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public abstract boolean validateEligibility(Warranty warranty,String claimant,LocalDateTime now);
        public abstract void closeClaim(String terminalStatus,String resolution,LocalDateTime at);
    }

    public static final class RmaCase extends RMARequest implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String serial;
        public final String customerId;
        public final String problem;
        public final LocalDateTime createdAt;
        public final String type;
        public String status = "Chờ kỹ thuật tiếp nhận";
        public String assignedTo = "Chưa phân công";
        public String diagnosis = "";
        public String resolutionNotes = "";
        public String replacementParts = "";
        public LocalDateTime updatedAt;
        public RmaCase(String id, String serial, String customerId, String problem, LocalDateTime createdAt) {
            this(id, serial, customerId, problem, createdAt, "Bảo hành");
        }
        public RmaCase(String id, String serial, String customerId, String problem, LocalDateTime createdAt, String type) {
            this.id = id;
            this.serial = serial;
            this.customerId = customerId;
            this.problem = problem;
            this.createdAt = createdAt;
            this.type = type;
            this.updatedAt = createdAt;
        }
        @Override public boolean validateEligibility(Warranty warranty,String claimant,LocalDateTime now) {
            return warranty!=null && warranty.serial.equals(serial) && warranty.customerId.equals(customerId)
                    && customerId.equals(claimant) && warranty.isValid(now) && problem!=null && !problem.isBlank();
        }
        @Override public void closeClaim(String terminalStatus,String resolution,LocalDateTime at) {
            if(!java.util.Set.of("Hoàn tất","Từ chối").contains(terminalStatus) || resolution==null || resolution.isBlank())
                throw new IllegalArgumentException("A closed RMA requires a terminal status and resolution");
            status=terminalStatus;resolutionNotes=resolution.trim();updatedAt=at;
        }
    }

    public static final class WarrantyVerification implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id, serial, customerId, problem;
        public final LocalDateTime createdAt;
        public String status = "Chờ xác minh";
        public String result = "";
        public WarrantyVerification(String id, String serial, String customerId, String problem, LocalDateTime createdAt) {
            this.id = id; this.serial = serial; this.customerId = customerId; this.problem = problem; this.createdAt = createdAt;
        }
    }

    public static final class ShipmentEvent implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String orderId;
        public final String milestone;
        public final String location;
        public final String detail;
        public final LocalDateTime occurredAt;
        public ShipmentEvent(String id, String orderId, String milestone, String location, String detail, LocalDateTime occurredAt) {
            this.id = id; this.orderId = orderId; this.milestone = milestone; this.location = location;
            this.detail = detail; this.occurredAt = occurredAt;
        }
    }

    public static final class AppNotification implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String audience;
        public final String title;
        public final String message;
        public final LocalDateTime createdAt;
        public boolean read;
        public AppNotification(String id, String audience, String title, String message, LocalDateTime createdAt) {
            this.id = id; this.audience = audience; this.title = title; this.message = message; this.createdAt = createdAt;
        }
    }

    public static final class ConsultationNote implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String orderId;
        public final String author;
        public final String note;
        public final LocalDateTime createdAt;
        public ConsultationNote(String id, String orderId, String author, String note, LocalDateTime createdAt) {
            this.id = id; this.orderId = orderId; this.author = author; this.note = note; this.createdAt = createdAt;
        }
    }

    public static final class InteractionEvent implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final LocalDateTime occurredAt;
        public final String type;
        public final String detail;
        /** Optional structured links; null for legacy events without reliable attribution. */
        public final String customerId;
        public final String variantId;
        public final String anonymousSessionKey,requestId,eventSource,consentScope,searchQuery,filters,sortBy,eventContext;
        public final Integer page;
        public InteractionEvent(String type, String detail) { this(type,detail,LocalDateTime.now()); }
        public InteractionEvent(String type, String detail, LocalDateTime occurredAt) { this(type,detail,occurredAt,null,null); }
        public InteractionEvent(String type,String detail,LocalDateTime occurredAt,String customerId,String variantId) {
            this(type,detail,occurredAt,customerId,variantId,null,null,"WEB",null,null,null,null,null,detail);
        }
        public InteractionEvent(String type,String detail,LocalDateTime occurredAt,String customerId,String variantId,
                                String anonymousSessionKey,String requestId,String eventSource,String consentScope,
                                String searchQuery,String filters,String sortBy,Integer page,String eventContext) {
            this.type=type;this.detail=detail;this.occurredAt=occurredAt;this.customerId=customerId;this.variantId=variantId;
            this.anonymousSessionKey=anonymousSessionKey;this.requestId=requestId;this.eventSource=eventSource;this.consentScope=consentScope;
            this.searchQuery=searchQuery;this.filters=filters;this.sortBy=sortBy;this.page=page;this.eventContext=eventContext;
        }
        public boolean validateConsent(boolean consented) { return consented && occurredAt!=null && type!=null && !type.isBlank(); }
    }

    public static final class RecommendationImpression implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id, requestId, customerId, primaryId, accessoryId, strategy, modelVersion;
        public final int rank;
        public final Double score;
        public final LocalDateTime shownAt;
        public RecommendationImpression(String id,String requestId,String customerId,String primaryId,String accessoryId,
                                        int rank,String strategy,String modelVersion,LocalDateTime shownAt) {
            this(id,requestId,customerId,primaryId,accessoryId,rank,(Double)null,strategy,modelVersion,shownAt);
        }
        public RecommendationImpression(String id,String requestId,String customerId,String primaryId,String accessoryId,
                                        int rank,double score,String strategy,String modelVersion,LocalDateTime shownAt) {
            this(id,requestId,customerId,primaryId,accessoryId,rank,Double.valueOf(score),strategy,modelVersion,shownAt);
        }
        public RecommendationImpression(String id,String requestId,String customerId,String primaryId,String accessoryId,
                                        int rank,Double score,String strategy,String modelVersion,LocalDateTime shownAt) {
            this.id=id; this.requestId=requestId; this.customerId=customerId; this.primaryId=primaryId; this.accessoryId=accessoryId;
            this.rank=rank; this.score=score!=null && Double.isFinite(score)?score:null; this.strategy=strategy; this.modelVersion=modelVersion; this.shownAt=shownAt;
        }
    }

    public static final class AuditEntry implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final LocalDateTime occurredAt;
        public final String actor;
        public final String action;
        public final String detail;
        public AuditEntry(String actor, String action, String detail) {
            this(actor,action,detail,LocalDateTime.now());
        }
        public AuditEntry(String actor,String action,String detail,LocalDateTime occurredAt) {
            this.actor=actor;this.action=action;this.detail=detail;this.occurredAt=occurredAt;
        }
    }

    public static final class RestockRequest implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String variantId;
        public final int quantity;
        public final String supplier;
        public final LocalDateTime createdAt;
        public String status = "Chờ nhà cung cấp xác nhận";
        public int leadDays = 5;
        public String supplierReference = "";
        public RestockRequest(String id, String variantId, int quantity, String supplier, LocalDateTime createdAt) {
            this.id = id; this.variantId = variantId; this.quantity = quantity; this.supplier = supplier; this.createdAt = createdAt;
        }
    }

    public static final class SupplierReturn implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id, variantId, serial, reason, supplier, createdBy;
        public final LocalDateTime createdAt;
        public String status = "Chờ bàn giao";
        public SupplierReturn(String id, String variantId, String serial, String reason, String supplier, String createdBy) {
            this(id,variantId,serial,reason,supplier,createdBy,LocalDateTime.now());
        }
        public SupplierReturn(String id,String variantId,String serial,String reason,String supplier,String createdBy,LocalDateTime createdAt) {
            this.id = id; this.variantId = variantId; this.serial = serial; this.reason = reason;
            this.supplier = supplier; this.createdBy = createdBy; this.createdAt=createdAt;
        }
    }

    public static final class CompatibilityReview implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final String id;
        public final String primaryVariantId;
        public final String accessoryVariantId;
        public final String reason;
        public final LocalDateTime createdAt;
        public String status = "Chờ rà soát";
        public String reviewedBy = "";
        public String reviewNote = "";
        public LocalDateTime reviewedAt;
        public CompatibilityReview(String id, String primaryVariantId, String accessoryVariantId, String reason, LocalDateTime createdAt) {
            this.id = id; this.primaryVariantId = primaryVariantId; this.accessoryVariantId = accessoryVariantId;
            this.reason = reason; this.createdAt = createdAt;
        }
    }
}
