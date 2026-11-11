package vn.techzone.backend;

import vn.techzone.application.TechZoneStore;
import vn.techzone.domain.Payment;
import vn.techzone.domain.Shipment;
import vn.techzone.domain.UnitAllocation;
import vn.techzone.domain.TechZoneModels.*;
import vn.techzone.infrastructure.DemoStoreRepository;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * One-time compatibility reader for pre-normalization SQLite files that contain app_state and legacy tables.
 * SqliteStore runs this importer only against a disposable copy, then persists to RelationalSchema.
 * Do not use this class for normal reads or writes of the active database.
 */
final class LegacySqliteStore implements AutoCloseable {
    private final Connection db;
    private final Path dbPath;
    public record State(long revision, byte[] bytes) { }

    public static boolean hasState(Path path) throws Exception {
        if (!Files.exists(path)) return false;
        Class.forName("org.sqlite.JDBC");
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement s=c.createStatement()) {
            try (ResultSet r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='app_state'")) {
                if(!r.next()) return false;
            }
            try (ResultSet r=s.executeQuery("SELECT 1 FROM app_state WHERE id=1")) { return r.next(); }
        }
    }

    public LegacySqliteStore(Path path, TechZoneStore initial) throws Exception {
        Class.forName("org.sqlite.JDBC");
        dbPath=path.toAbsolutePath().normalize();
        db=DriverManager.getConnection("jdbc:sqlite:"+dbPath);
        try {
            try(Statement s=db.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
                s.execute("CREATE TABLE IF NOT EXISTS app_state(id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, snapshot BLOB NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS products(id TEXT PRIMARY KEY, sku TEXT NOT NULL UNIQUE, name TEXT NOT NULL, category TEXT NOT NULL, brand TEXT NOT NULL, price INTEGER NOT NULL, sale_price INTEGER NOT NULL, active INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS product_specs(product_id TEXT NOT NULL REFERENCES products(id), spec_key TEXT NOT NULL, spec_value TEXT NOT NULL, PRIMARY KEY(product_id,spec_key))");
                s.execute("CREATE TABLE IF NOT EXISTS inventory(product_id TEXT PRIMARY KEY REFERENCES products(id), available INTEGER NOT NULL CHECK(available>=0), reserved INTEGER NOT NULL CHECK(reserved>=0), allocated INTEGER NOT NULL CHECK(allocated>=0), threshold INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS sales_orders(id TEXT PRIMARY KEY, number TEXT NOT NULL, customer_id TEXT NOT NULL, status TEXT NOT NULL, total INTEGER NOT NULL, created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS order_lines(order_id TEXT NOT NULL REFERENCES sales_orders(id), product_id TEXT NOT NULL REFERENCES products(id), quantity INTEGER NOT NULL, unit_price INTEGER NOT NULL, PRIMARY KEY(order_id,product_id))");
                s.execute("CREATE TABLE IF NOT EXISTS inventory_units(serial TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), status TEXT NOT NULL, order_id TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS market_offers(product_id TEXT PRIMARY KEY, source_sku TEXT NOT NULL, source_url TEXT NOT NULL, price INTEGER NOT NULL, availability TEXT NOT NULL, fetched_at TEXT NOT NULL)");
            }
            migrateCore();
            DesignSchemaMigration.migrate(db,dbPath,() -> { if(readRaw()!=null) read(); });
            migrateEventReferences();
            migrateCompatibilityReviews();
            enforceDesignUniqueness();
            FulfillmentDesignMigration.migrate(db,dbPath,readRaw()!=null,() -> { if(readRaw()!=null) read(); });
            DesignFieldMigration.migrate(db,dbPath,readRaw()!=null,() -> { if(readRaw()!=null) read(); });
            if(readRaw()==null) save(initial,-1);
        } catch(Exception ex) { try { db.close(); } catch(SQLException suppressed) { ex.addSuppressed(suppressed); } throw ex; }
    }

    public Path databasePath() { return dbPath; }
    public record MarketOffer(String productId,String sourceSku,String url,long price,String availability,String fetchedAt) { }
    public synchronized List<MarketOffer> marketOffers() throws SQLException {
        List<MarketOffer> result=new ArrayList<>();
        try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT product_id,source_sku,source_url,price,availability,fetched_at FROM market_offers ORDER BY product_id")) {
            while(r.next()) result.add(new MarketOffer(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6)));
        }
        return result;
    }

    private boolean hasColumn(String table,String column) throws SQLException {
        try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next()) if(column.equals(r.getString("name"))) return true;
            return false;
        }
    }

    /** Enforce the order keys specified by the design without inventing missing legacy keys. */
    private void enforceDesignUniqueness() throws SQLException {
        db.setAutoCommit(false);
        try (Statement s=db.createStatement()) {
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS sales_orders_number_unique ON sales_orders(number)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS sales_orders_idempotency_unique ON sales_orders(idempotency_key) WHERE idempotency_key <> ''");
            db.commit();
        } catch (SQLException ex) {
            db.rollback();
            throw new SQLException("Cannot enforce unique order numbers and idempotency keys; check existing orders", ex);
        } finally { db.setAutoCommit(true); }
    }
    private void addColumn(String table,String name,String type) throws SQLException {
        if(!hasColumn(table,name)) try(Statement s=db.createStatement()) { s.execute("ALTER TABLE "+table+" ADD COLUMN "+name+" "+type); }
    }
    private boolean hasTable(String table) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            q.setString(1,table); try(ResultSet r=q.executeQuery()) { return r.next(); }
        }
    }
    private String preferredColumn(String table,String alias,String design,String legacy,String fallback) throws SQLException {
        boolean hasDesign=hasColumn(table,design),hasLegacy=hasColumn(table,legacy);
        if(hasDesign && hasLegacy && !design.equals(legacy))return "COALESCE("+alias+"."+design+","+alias+"."+legacy+")";
        if(hasDesign)return alias+"."+design;
        if(hasLegacy)return alias+"."+legacy;
        return fallback;
    }
    private void migrateCore() throws Exception {
        boolean coreReady=hasColumn("sales_orders","idempotency_key") && hasColumn("products","warranty_months")
                && hasColumn("order_lines","allocated_qty") && hasColumn("inventory","warehouse");
        boolean supportReady=hasTable("warranties") && hasTable("rma_cases");
        boolean accountsReady=hasTable("customers") && hasTable("shopping_carts") && hasTable("cart_items");
        boolean discoveryReady=hasTable("compatibility_rules") && hasTable("interaction_events");
        boolean fulfillmentReady=hasTable("payments") && hasTable("shipments") && hasTable("unit_allocations");
        boolean impressionsReady=hasTable("recommendation_impressions");
        if(coreReady && supportReady && accountsReady && discoveryReady && fulfillmentReady && impressionsReady) return;
        State old=readRaw();
        if(old!=null) {
            Path backup=dbPath.resolveSibling(dbPath.getFileName()+".pre-core-"+System.currentTimeMillis()+".bak");
            try(PreparedStatement statement=db.prepareStatement("VACUUM INTO ?")) { statement.setString(1,backup.toString()); statement.execute(); }
        }
        db.setAutoCommit(false);
        try {
            TechZoneStore imported=old==null?null:decodeSession(old.bytes());
            try {
            if(imported!=null && coreReady) loadCoreRows(imported,supportReady,accountsReady,discoveryReady,fulfillmentReady,impressionsReady);
            addColumn("products","description","TEXT NOT NULL DEFAULT ''");
            addColumn("products","color","TEXT NOT NULL DEFAULT ''");
            addColumn("products","warranty_months","INTEGER NOT NULL DEFAULT 0");
            addColumn("inventory","warehouse","TEXT NOT NULL DEFAULT ''");
            // writeCoreRows also populates the later design fields on very old snapshots.
            for(String name:List.of("unit_id","variant_id","serial_imei","batch_number","received_at","warehouse_location","unit_status"))
                addColumn("inventory_units",name,"TEXT");
            addColumn("order_lines","reserved_qty","INTEGER NOT NULL DEFAULT 0");
            addColumn("order_lines","allocated_qty","INTEGER NOT NULL DEFAULT 0");
            for(String name:List.of("customer_name","shipping_address","payment_method","idempotency_key","promotion_code",
                    "payment_reference","tracking_code","carrier","package_dimensions","sales_verified_by","pending_reason"))
                addColumn("sales_orders",name,"TEXT NOT NULL DEFAULT ''");
            for(String name:List.of("subtotal","discount","tax","shipping_fee","package_weight_grams"))
                addColumn("sales_orders",name,"INTEGER NOT NULL DEFAULT 0");
            for(String name:List.of("sales_verified_at","paid_at","delivered_at","reconciliation_due_at"))
                addColumn("sales_orders",name,"TEXT");
            try(Statement s=db.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS warranties(serial TEXT PRIMARY KEY REFERENCES inventory_units(serial), variant_id TEXT NOT NULL REFERENCES products(id), order_id TEXT NOT NULL REFERENCES sales_orders(id), customer_id TEXT NOT NULL, activated_at TEXT NOT NULL, expires_at TEXT NOT NULL, status TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS rma_cases(id TEXT PRIMARY KEY, serial TEXT NOT NULL REFERENCES warranties(serial), customer_id TEXT NOT NULL, problem TEXT NOT NULL, created_at TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, assigned_to TEXT NOT NULL, diagnosis TEXT NOT NULL, resolution_notes TEXT NOT NULL, replacement_parts TEXT NOT NULL, updated_at TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS customers(id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL, address TEXT NOT NULL, password_salt BLOB NOT NULL, password_hash BLOB NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS shopping_carts(customer_id TEXT PRIMARY KEY REFERENCES customers(id))");
                s.execute("CREATE TABLE IF NOT EXISTS cart_items(customer_id TEXT NOT NULL REFERENCES shopping_carts(customer_id), product_id TEXT NOT NULL REFERENCES products(id), quantity INTEGER NOT NULL CHECK(quantity>0), PRIMARY KEY(customer_id,product_id))");
                s.execute("CREATE TABLE IF NOT EXISTS compatibility_rules(left_variant_id TEXT NOT NULL REFERENCES products(id), right_variant_id TEXT NOT NULL REFERENCES products(id), PRIMARY KEY(left_variant_id,right_variant_id))");
                s.execute("CREATE TABLE IF NOT EXISTS interaction_events(seq INTEGER PRIMARY KEY, event_type TEXT NOT NULL, detail TEXT NOT NULL, occurred_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS payments(order_id TEXT PRIMARY KEY REFERENCES sales_orders(id), method TEXT NOT NULL, provider_ref TEXT NOT NULL, payment_status TEXT NOT NULL, authorized_at TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS shipments(order_id TEXT PRIMARY KEY REFERENCES sales_orders(id), tracking_code TEXT NOT NULL UNIQUE, carrier TEXT NOT NULL, package_weight_grams INTEGER NOT NULL, package_dimensions TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS unit_allocations(serial TEXT PRIMARY KEY REFERENCES inventory_units(serial), order_id TEXT NOT NULL, product_id TEXT NOT NULL, FOREIGN KEY(order_id,product_id) REFERENCES order_lines(order_id,product_id))");
                s.execute("CREATE TABLE IF NOT EXISTS recommendation_impressions(id TEXT PRIMARY KEY, request_id TEXT NOT NULL, customer_id TEXT NOT NULL REFERENCES customers(id), primary_id TEXT NOT NULL REFERENCES products(id), accessory_id TEXT NOT NULL REFERENCES products(id), rank_position INTEGER NOT NULL CHECK(rank_position>0), strategy TEXT NOT NULL, model_version TEXT NOT NULL, shown_at TEXT NOT NULL)");
            }
            addColumn("recommendation_impressions","score","REAL");
            if(imported!=null) { imported.validateInventoryIntegrity(); imported.validateSupportIntegrity(); writeCoreRows(imported); }
            } finally { if(imported!=null) imported.closeRepository(); }
            db.commit();
        } catch(Exception ex) { db.rollback(); throw ex; }
        finally { db.setAutoCommit(true); }
    }

    /** Add nullable structured event links; legacy unverified attribution remains NULL. */
    private void migrateEventReferences() throws Exception {
        if(hasColumn("interaction_events","customer_id") && hasColumn("interaction_events","variant_id"))return;
        if(readRaw()!=null) {
            Path backup=dbPath.resolveSibling(dbPath.getFileName()+".pre-events-"+System.currentTimeMillis()+".bak");
            try(PreparedStatement statement=db.prepareStatement("VACUUM INTO ?")) { statement.setString(1,backup.toString());statement.execute(); }
        }
        db.setAutoCommit(false);
        try {
            addColumn("interaction_events","customer_id","TEXT REFERENCES customers(id)");
            addColumn("interaction_events","variant_id","TEXT REFERENCES product_variants(id)");
            try(Statement s=db.createStatement()) {
                s.execute("CREATE INDEX IF NOT EXISTS interaction_customer_idx ON interaction_events(customer_id)");
                s.execute("CREATE INDEX IF NOT EXISTS interaction_variant_idx ON interaction_events(variant_id)");
                try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { if(r.next())throw new SQLException("Vi phạm khóa ngoại sự kiện."); }
            }
            if(readRaw()!=null)read();
            db.commit();
        } catch(Exception ex) { db.rollback();throw ex; }
        finally { db.setAutoCommit(true); }
    }

    /** Transfer review history from the legacy snapshot into relational rows. */
    private void migrateCompatibilityReviews() throws Exception {
        if(hasTable("compatibility_reviews"))return;
        State old=read(); TechZoneStore imported=old==null?null:decodeSession(old.bytes());
        try {
            if(old!=null) {
                Path backup=dbPath.resolveSibling(dbPath.getFileName()+".pre-reviews-"+System.currentTimeMillis()+".bak");
                try(PreparedStatement statement=db.prepareStatement("VACUUM INTO ?")) { statement.setString(1,backup.toString());statement.execute(); }
            }
            db.setAutoCommit(false);
            try {
                try(Statement s=db.createStatement()) {
                    s.execute("CREATE TABLE compatibility_reviews(id TEXT PRIMARY KEY, seq INTEGER NOT NULL UNIQUE, primary_variant_id TEXT NOT NULL REFERENCES product_variants(id), accessory_variant_id TEXT NOT NULL REFERENCES product_variants(id), reason TEXT NOT NULL, created_at TEXT NOT NULL, status TEXT NOT NULL, reviewed_by TEXT NOT NULL, review_note TEXT NOT NULL, reviewed_at TEXT)");
                }
                if(imported!=null)writeCompatibilityReviews(imported);
                if(old!=null)read();
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { if(r.next())throw new SQLException("Vi phạm khóa ngoại rà soát tương thích."); }
                db.commit();
            } catch(Exception ex) { db.rollback();throw ex; }
            finally { db.setAutoCommit(true); }
        } finally { if(imported!=null)imported.closeRepository(); }
    }

    private void writeCompatibilityReviews(TechZoneStore store) throws SQLException {
        try(PreparedStatement p=db.prepareStatement("INSERT INTO compatibility_reviews(id,seq,primary_variant_id,accessory_variant_id,reason,created_at,status,reviewed_by,review_note,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            int seq=0;
            for(CompatibilityReview review:store.compatibilityReviews()) {
                p.setString(1,review.id);p.setInt(2,++seq);p.setString(3,review.primaryVariantId);p.setString(4,review.accessoryVariantId);
                p.setString(5,review.reason);p.setString(6,time(review.createdAt));p.setString(7,review.status);
                p.setString(8,review.reviewedBy);p.setString(9,review.reviewNote);p.setString(10,time(review.reviewedAt));p.addBatch();
            }
            p.executeBatch();
        }
    }

    public static byte[] encode(TechZoneStore store) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(b)) { out.writeObject(store); }
        return b.toByteArray();
    }
    public static TechZoneStore decode(byte[] bytes) throws IOException { return decode(bytes,false); }
    public static TechZoneStore decodeSession(byte[] bytes) throws IOException { return decode(bytes,true); }
    private static TechZoneStore decode(byte[] bytes,boolean preserveSession) throws IOException {
        TechZoneStore.preserveDeserializedSession(preserveSession);
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            DemoStoreRepository.installFilter(in); Object value=in.readObject();
            if(!(value instanceof TechZoneStore store)) throw new IOException("Dữ liệu không thuộc TechZone.");
            return store;
        } catch(ClassNotFoundException | RuntimeException ex) { throw new IOException("Không đọc được dữ liệu TechZone.",ex); }
        finally { TechZoneStore.preserveDeserializedSession(false); }
    }
    private State readRaw() throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT revision,snapshot FROM app_state WHERE id=1"); ResultSet r=q.executeQuery()) {
            return r.next()?new State(r.getLong(1),r.getBytes(2)):null;
        }
    }
    public synchronized State read() throws SQLException,IOException {
        State raw=readRaw(); if(raw==null) return null;
        TechZoneStore store=decodeSession(raw.bytes());
        try { loadCoreRows(store,true,true,true,true,true); return new State(raw.revision(),encode(store)); }
        finally { store.closeRepository(); }
    }

    /** One-time additive upgrade; existing products and transactions are retained. */
    public synchronized void importRealCatalog() throws Exception {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS demo_migrations(name TEXT PRIMARY KEY)");
            try (ResultSet r = s.executeQuery("SELECT 1 FROM demo_migrations WHERE name='catalog-real-v1'")) {
                if (r.next()) return;
            }
        }
        State state = read();
        TechZoneStore current = decode(state.bytes());
        try {
            if (current.importRealCatalog() > 0) save(current, state.revision());
            try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO demo_migrations(name) VALUES(?)")) {
                p.setString(1, "catalog-real-v1"); p.executeUpdate();
            }
        } finally { current.closeRepository(); }
    }
    public synchronized void importVerifiedManufacturerPair() throws Exception {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM demo_migrations WHERE name='verified-pair-am5-v1'")) {
            if (r.next()) return;
        }
        State state = read();
        TechZoneStore current = decode(state.bytes());
        try {
            if (current.seedVerifiedManufacturerPair()) save(current,state.revision());
            try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO demo_migrations(name) VALUES(?)")) {
                p.setString(1,"verified-pair-am5-v1"); p.executeUpdate();
            }
        } finally { current.closeRepository(); }
    }
    public synchronized void importVerifiedRyzen7600Pairs() throws Exception {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT 1 FROM demo_migrations WHERE name='verified-pair-7600-v1'")) {
            if (r.next()) return;
        }
        State state=read(); TechZoneStore current=decode(state.bytes());
        try {
            if(current.seedVerifiedRyzen7600Pairs()) save(current,state.revision());
            try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO demo_migrations(name) VALUES(?)")) {
                p.setString(1,"verified-pair-7600-v1"); p.executeUpdate();
            }
        } finally { current.closeRepository(); }
    }
    public synchronized void importVerifiedStorageFacts() throws Exception {
        try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT 1 FROM demo_migrations WHERE name='verified-m2-facts-v1'")) {
            if(r.next()) return;
        }
        State state=read(); TechZoneStore current=decode(state.bytes());
        try {
            if(current.seedVerifiedStorageFacts()) save(current,state.revision());
            try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO demo_migrations(name) VALUES(?)")) {
                p.setString(1,"verified-m2-facts-v1"); p.executeUpdate();
            }
        } finally { current.closeRepository(); }
    }
    private static final LocalDateTime UNRECORDED_TIME=LocalDateTime.of(1900,1,1,0,0,1);
    private static final String UNRECORDED_TIME_VALUE=UNRECORDED_TIME.toString();
    private static LocalDateTime time(String value) {
        if(value==null || value.isBlank())return null;
        LocalDateTime parsed=LocalDateTime.parse(value);
        return UNRECORDED_TIME.equals(parsed)?null:parsed;
    }
    private static String time(LocalDateTime value) { return value==null?UNRECORDED_TIME_VALUE:value.toString(); }
    private static boolean cartChanged(List<CartItem> before,List<CartItem> after) {
        if(before.size()!=after.size())return true;
        Map<String,String> values=new HashMap<>();
        for(CartItem item:before)values.put(item.variantId,item.quantity+":"+item.unitPriceSnapshot);
        for(CartItem item:after)if(!Objects.equals(values.get(item.variantId),item.quantity+":"+item.unitPriceSnapshot))return true;
        return false;
    }

    private void loadCoreRows(TechZoneStore store,boolean loadSupport,boolean loadAccounts,boolean loadDiscovery,boolean loadFulfillment,boolean loadImpressions) throws SQLException,IOException {
        try {
            Map<String,Map<String,String>> specs=new LinkedHashMap<>();
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT product_id,spec_key,spec_value FROM product_specs ORDER BY rowid")) {
                while(r.next()) specs.computeIfAbsent(r.getString(1),k->new LinkedHashMap<>()).put(r.getString(2),r.getString(3));
            }
            List<ProductVariant> products=new ArrayList<>();
            boolean hasVariants=hasTable("product_variants");
            String productQuery;
            if(hasVariants) {
                String variantId=preferredColumn("product_variants","v","variant_id","id","v.id");
                String productId=preferredColumn("product_variants","v","product_id","id","v.id");
                String productName=preferredColumn("products","p","product_name","name","''");
                String productBrand=preferredColumn("products","p","brand_name","brand","''");
                String productActive=preferredColumn("products","p","is_active","active","1");
                String variantActive=preferredColumn("product_variants","v","is_active","active","1");
                String productCreated=hasColumn("products","created_at")?"p.created_at":"NULL";
                String variantUpdated=hasColumn("product_variants","updated_at")?"v.updated_at":"NULL";
                productQuery="SELECT "+variantId+","+productId+","+productName+",v.sku,v.variant_name,p.category,"+productBrand
                        +",p.description,v.color,v.price,v.sale_price,v.warranty_months,"+productActive+","+variantActive+","+productCreated+","+variantUpdated
                        +" FROM product_variants v JOIN products p ON p.id=v.product_id ORDER BY v.rowid";
            } else {
                String productId=preferredColumn("products","p","product_id","id","p.id");
                String productName=preferredColumn("products","p","product_name","name","''");
                String productBrand=preferredColumn("products","p","brand_name","brand","''");
                String productActive=preferredColumn("products","p","is_active","active","1");
                String createdAt=hasColumn("products","created_at")?"p.created_at":"NULL";
                productQuery="SELECT "+productId+","+productId+","+productName+",p.sku,p.name,p.category,"+productBrand
                        +",p.description,p.color,p.price,p.sale_price,p.warranty_months,"+productActive+","+productActive+","+createdAt+","+createdAt+" FROM products p ORDER BY p.rowid";
            }
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(productQuery)) {
                while(r.next()) {
                    ProductVariant p=new ProductVariant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getLong(10),r.getLong(11),r.getInt(12),specs.getOrDefault(r.getString(1),Map.of()),r.getInt(13)!=0,r.getInt(14)!=0);
                    p.createdAt=time(r.getString(15));p.updatedAt=time(r.getString(16));
                    products.add(p);
                }
            }
            List<InventoryBalance> balances=new ArrayList<>();
            String inventoryQuery;
            if(hasTable("inventory_balances")) {
                String warehouse=preferredColumn("inventory_balances","b","warehouse_code","warehouse","''");
                inventoryQuery="SELECT variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,"+warehouse+" FROM inventory_balances b ORDER BY b.rowid";
            } else inventoryQuery="SELECT product_id,available,reserved,allocated,threshold,warehouse FROM inventory ORDER BY rowid";
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(inventoryQuery)) {
                while(r.next()) balances.add(new InventoryBalance(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getString(6)));
            }
            List<InventoryUnit> units=new ArrayList<>();
            String unitSerial=preferredColumn("inventory_units","u","serial_imei","serial","NULL");
            String unitVariant=preferredColumn("inventory_units","u","variant_id","product_id","NULL");
            String unitStatus=preferredColumn("inventory_units","u","unit_status","status","'AVAILABLE'");
            String receivedAt=hasColumn("inventory_units","received_at")?"u.received_at":"NULL";
            String batchNumber=hasColumn("inventory_units","batch_number")?"u.batch_number":"NULL";
            String warehouseLocation=hasColumn("inventory_units","warehouse_location")?"u.warehouse_location":"NULL";
            String unitQuery="SELECT "+unitSerial+","+unitVariant+","+unitStatus+",u.order_id,"+receivedAt+","+batchNumber+","+warehouseLocation+" FROM inventory_units u ORDER BY u.rowid";
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(unitQuery)) {
                while(r.next()) { InventoryUnit u=new InventoryUnit(r.getString(1),r.getString(2),time(r.getString(5)),r.getString(6),r.getString(7)); u.status=UnitStatus.valueOf(r.getString(3)); u.orderId=r.getString(4); units.add(u); }
            }
            Map<String,Payment> payments=new LinkedHashMap<>(); Map<String,Shipment> shipments=new LinkedHashMap<>();
            if(loadFulfillment) {
                boolean designedPayment=hasColumn("payments","payment_id");
                String paymentQuery=designedPayment
                        ? "SELECT p.payment_id,p.order_id,p.payment_method,p.provider_ref,p.idempotency_key,p.amount,p.currency_code,p.payment_status,COALESCE(p.authorized_at,o.paid_at),p.captured_at,p.updated_at FROM payments p JOIN sales_orders o ON o.id=p.order_id"
                        : "SELECT p.order_id,p.order_id,p.method,p.provider_ref,o.idempotency_key,o.total,'VND',p.payment_status,COALESCE(p.authorized_at,o.paid_at),NULL,NULL FROM payments p JOIN sales_orders o ON o.id=p.order_id";
                boolean designedShipment=hasColumn("shipments","shipment_id");
                String shipmentQuery=designedShipment
                        ? "SELECT s.shipment_id,s.order_id,s.tracking_code,s.carrier_name,s.package_weight_grams,s.package_dimensions,s.shipped_at,s.shipment_status,o.delivered_at FROM shipments s JOIN sales_orders o ON o.id=s.order_id"
                        : "SELECT s.order_id,s.order_id,s.tracking_code,s.carrier,s.package_weight_grams,s.package_dimensions,NULL,o.status,o.delivered_at FROM shipments s JOIN sales_orders o ON o.id=s.order_id";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(paymentQuery)) {
                    while(r.next()) payments.put(r.getString(2),new Payment(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7),r.getString(8),time(r.getString(9)),time(r.getString(10)),time(r.getString(11))));
                }
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(shipmentQuery)) {
                    while(r.next()) shipments.put(r.getString(2),new Shipment(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),time(r.getString(7)),r.getString(8),time(r.getString(9))));
                }
            }
            Map<String,SalesOrder> orders=new LinkedHashMap<>();
            String orderNumber=preferredColumn("sales_orders","o","order_number","number","''");
            String orderStatus=preferredColumn("sales_orders","o","order_status","status","'PENDING_PAYMENT'");
            String orderTotal=preferredColumn("sales_orders","o","total_amount","total","0");
            String orderTax=preferredColumn("sales_orders","o","tax_amount","tax","0");
            String orderQuery="SELECT o.id,"+orderNumber+" AS order_number,o.customer_id,o.customer_name,o.shipping_address,o.payment_method,o.idempotency_key,o.promotion_code,o.created_at,"
                    +orderStatus+" AS order_status,"+orderTotal+" AS total_amount,o.subtotal,o.discount,"+orderTax+" AS tax_amount,o.shipping_fee,o.payment_reference,o.tracking_code,o.carrier,"
                    +"o.package_weight_grams,o.package_dimensions,o.sales_verified_by,o.sales_verified_at,o.pending_reason,o.paid_at,o.delivered_at,o.reconciliation_due_at FROM sales_orders o ORDER BY o.rowid";
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(orderQuery)) {
                while(r.next()) {
                    Payment payment=loadFulfillment?payments.get(r.getString("id")):null;
                    if(loadFulfillment && payment==null) throw new IOException("Thiếu thông tin thanh toán của đơn " + r.getString("id") + ".");
                    SalesOrder o=new SalesOrder(r.getString("id"),r.getString("order_number"),r.getString("customer_id"),r.getString("customer_name"),
                            r.getString("shipping_address"),payment==null?r.getString("payment_method"):payment.method(),r.getString("idempotency_key"),r.getString("promotion_code"),time(r.getString("created_at")));
                    o.status=OrderStatus.valueOf(r.getString("order_status")); o.total=r.getLong("total_amount"); o.subtotal=r.getLong("subtotal");
                    o.discount=r.getLong("discount"); o.tax=r.getLong("tax_amount"); o.shippingFee=r.getLong("shipping_fee");
                    o.paymentReference=r.getString("payment_reference"); o.trackingCode=r.getString("tracking_code"); o.carrier=r.getString("carrier");
                    o.packageWeightGrams=r.getInt("package_weight_grams"); o.packageDimensions=r.getString("package_dimensions");
                    o.salesVerifiedBy=r.getString("sales_verified_by"); o.salesVerifiedAt=time(r.getString("sales_verified_at"));
                    o.pendingReason=r.getString("pending_reason"); o.paidAt=time(r.getString("paid_at")); o.deliveredAt=time(r.getString("delivered_at"));
                    o.reconciliationDueAt=time(r.getString("reconciliation_due_at")); orders.put(o.id,o);
                    if(payment!=null) {
                        if("AUTHORIZED".equals(payment.status()) && !payment.verifyAuthorization()) throw new IOException("Payment authorization is missing verified reference or time.");
                        o.paymentReference=payment.providerReference(); o.paidAt=payment.authorizedAt();
                    }
                    if(loadFulfillment) {
                        Shipment shipment=shipments.get(o.id);
                        SalesOrder previous=store.order(o.id);
                        if(shipment==null && previous!=null && previous.trackingCode!=null && !previous.trackingCode.isBlank())
                            throw new IOException("Thiếu vận đơn của " + o.number + ".");
                        if(shipment!=null) {
                            if("DELIVERED".equals(shipment.status()) && !shipment.verifyDelivery()) throw new IOException("Delivered shipment is missing a delivery time.");
                            o.trackingCode=shipment.trackingCode(); o.carrier=shipment.carrier(); o.packageWeightGrams=(int)shipment.weightGrams(); o.packageDimensions=shipment.dimensions();
                            o.shippedAt=shipment.shippedAt();
                        }
                        else { o.trackingCode=""; o.carrier=""; o.packageWeightGrams=0; o.packageDimensions=""; }
                    }
                }
            }
            Map<String,OrderLine> lines=new LinkedHashMap<>();
            String itemTable=hasTable("order_items")?"order_items":"order_lines";
            String itemVariant=hasTable("order_items")?"variant_id":"product_id";
            String itemsQuery="SELECT order_id,"+itemVariant+",quantity,unit_price,reserved_qty,allocated_qty,"+(hasColumn(itemTable,"discount_amount")?"discount_amount":"0")+" FROM "+itemTable+" ORDER BY rowid";
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(itemsQuery)) {
                while(r.next()) {
                    SalesOrder o=orders.get(r.getString(1)); if(o==null) throw new IOException("Dòng hàng thiếu đơn tương ứng.");
                    OrderLine line=new OrderLine(r.getString(2),r.getInt(3),r.getLong(4)); line.reservedQuantity=r.getInt(5); line.allocatedQuantity=r.getInt(6);line.discountAmount=r.getLong(7);
                    if(!loadFulfillment) for(InventoryUnit u:units) if(u.status==UnitStatus.ALLOCATED && o.id.equals(u.orderId) && line.variantId.equals(u.variantId)) line.serials.add(u.serial);
                    o.lines.add(line); lines.put(o.id+"|"+line.variantId,line);
                }
            }
            if(loadFulfillment) {
                Map<String,InventoryUnit> bySerial=new HashMap<>(); for(InventoryUnit u:units) bySerial.put(u.serial,u);
                Set<String> assigned=new HashSet<>();
                boolean designAllocation=hasColumn("unit_allocations","allocation_id");
                String allocationQuery=designAllocation
                        ? "SELECT a.serial,a.order_id,a.product_id,a.allocation_id,a.unit_id,a.variant_id,a.order_item_id,i.order_item_id,a.allocated_by,a.scanned_at FROM unit_allocations a LEFT JOIN order_items i ON i.order_id=a.order_id AND i.variant_id=a.product_id ORDER BY a.rowid"
                        : "SELECT serial,order_id,product_id FROM unit_allocations ORDER BY rowid";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(allocationQuery)) {
                    while(r.next()) {
                        String serial=r.getString(1), orderId=r.getString(2), productId=r.getString(3);
                        InventoryUnit unit=bySerial.get(serial); OrderLine line=lines.get(orderId+"|"+productId);
                        if(unit==null || line==null || unit.status!=UnitStatus.ALLOCATED || !orderId.equals(unit.orderId)
                                || !productId.equals(unit.variantId) || !assigned.add(serial)) throw new IOException("Cấp phát Serial/IMEI không hợp lệ.");
                        if(designAllocation && (!serial.equals(r.getString(4)) || !serial.equals(r.getString(5))
                                || !productId.equals(r.getString(6)) || r.getString(7)==null || !r.getString(7).equals(r.getString(8))))
                            throw new IOException("Tham chiếu cấp phát không khớp với dòng đơn hàng hoặc Serial/IMEI.");
                        line.serials.add(serial);
                        if(designAllocation && (r.getString(9)!=null || r.getString(10)!=null))
                            line.recordAllocation(new UnitAllocation(serial,r.getString(7),serial,productId,"ALLOCATED",r.getString(9),time(r.getString(10))));
                    }
                }
                for(InventoryUnit u:units) if(u.status==UnitStatus.ALLOCATED && !assigned.contains(u.serial))
                    throw new IOException("Thiếu cấp phát Serial/IMEI " + u.serial + ".");
            }
            store.restoreRelationalCore(products,balances,units,new ArrayList<>(orders.values()));
            store.validateInventoryIntegrity();
            if(loadAccounts) {
                List<TechZoneStore.RelationalAccount> accounts=new ArrayList<>();
                boolean customerPhone=hasColumn("customers","phone_number");
                boolean customerTimes=hasColumn("customers","created_at") && hasColumn("customers","updated_at");
                String customerId=preferredColumn("customers","c","customer_id","id","NULL");
                String customerName=preferredColumn("customers","c","full_name","name","''");
                String accountQuery="SELECT "+customerId+","+customerName+",c.email,c.address,c.password_salt,c.password_hash,"+(customerPhone?"c.phone_number":"''")+","+
                        (customerTimes?"c.created_at,c.updated_at":"NULL,NULL")+" FROM customers c ORDER BY c.rowid";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(accountQuery)) {
                    while(r.next()) accounts.add(new TechZoneStore.RelationalAccount(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getBytes(6),r.getString(7),time(r.getString(8)),time(r.getString(9))));
                }
                Map<String,List<CartItem>> carts=new LinkedHashMap<>();
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT customer_id FROM shopping_carts ORDER BY rowid")) {
                    while(r.next()) carts.put(r.getString(1),new ArrayList<>());
                }
                boolean hasCartPriceSnapshot=hasColumn("cart_items","unit_price_snapshot");
                boolean hasCartItemAddedAt=hasColumn("cart_items","added_at");
                String cartQuery=hasCartPriceSnapshot
                        ? "SELECT customer_id,"+preferredColumn("cart_items","i","variant_id","product_id","NULL")+",quantity,unit_price_snapshot,"+(hasCartItemAddedAt?"added_at":"NULL")+" FROM cart_items i ORDER BY i.rowid"
                        : "SELECT customer_id,product_id,quantity,0,NULL FROM cart_items ORDER BY rowid";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(cartQuery)) {
                    while(r.next()) { List<CartItem> items=carts.get(r.getString(1)); if(items==null) throw new IOException("Dòng giỏ thiếu khách hàng."); LocalDateTime added=time(r.getString(5)); items.add(new CartItem(r.getString(2),r.getInt(3),r.getLong(4),added,added)); }
                }
                store.restoreRelationalAccounts(accounts,carts);
            }
            if(loadDiscovery) {
                List<TechZoneStore.RelationalPair> pairs=new ArrayList<>();
                String primary=preferredColumn("compatibility_rules","c","primary_variant_id","left_variant_id","NULL");
                String accessory=preferredColumn("compatibility_rules","c","accessory_variant_id","right_variant_id","NULL");
                String approved=hasColumn("compatibility_rules","is_approved")?"c.is_approved=1":"1=1";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT "+primary+","+accessory+" FROM compatibility_rules c WHERE "+approved+" ORDER BY c.rowid")) {
                    while(r.next()) pairs.add(new TechZoneStore.RelationalPair(r.getString(1),r.getString(2)));
                }
                List<InteractionEvent> events=new ArrayList<>();
                boolean eventReferences=hasColumn("interaction_events","customer_id") && hasColumn("interaction_events","variant_id");
                boolean eventDesign=eventReferences && hasColumn("interaction_events","request_id") && hasColumn("interaction_events","event_source");
                String eventQuery=eventDesign
                        ? "SELECT event_type,COALESCE(event_context,detail),occurred_at,customer_id,variant_id,anonymous_session_key,request_id,event_source,consent_scope,search_query,filters,sort_by,page FROM interaction_events ORDER BY seq"
                        : eventReferences?"SELECT event_type,detail,occurred_at,customer_id,variant_id,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL FROM interaction_events ORDER BY seq"
                        : "SELECT event_type,detail,occurred_at,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL FROM interaction_events ORDER BY seq";
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(eventQuery)) {
                    while(r.next()) {
                        Integer page=r.getObject(13)==null?null:r.getInt(13);
                        events.add(new InteractionEvent(r.getString(1),r.getString(2),time(r.getString(3)),r.getString(4),r.getString(5),
                                r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),page,r.getString(2)));
                    }
                }
                store.restoreRelationalDiscovery(pairs,events);
            }
            if(hasTable("compatibility_reviews")) {
                List<CompatibilityReview> reviews=new ArrayList<>();
                try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT id,primary_variant_id,accessory_variant_id,reason,created_at,status,reviewed_by,review_note,reviewed_at FROM compatibility_reviews ORDER BY seq")) {
                    while(r.next()) {
                        CompatibilityReview review=new CompatibilityReview(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)));
                        review.status=r.getString(6);review.reviewedBy=r.getString(7);review.reviewNote=r.getString(8);review.reviewedAt=time(r.getString(9));reviews.add(review);
                    }
                }
                store.restoreRelationalCompatibilityReviews(reviews);
            }
            if(loadImpressions) {
                List<RecommendationImpression> impressions=new ArrayList<>();
                String impressionId=preferredColumn("recommendation_impressions","i","impression_id","id","NULL");
                String primary=preferredColumn("recommendation_impressions","i","primary_variant_id","primary_id","NULL");
                String accessory=preferredColumn("recommendation_impressions","i","accessory_variant_id","accessory_id","NULL");
                String score=hasColumn("recommendation_impressions","score")?"i.score":"NULL";
                String generated=preferredColumn("recommendation_impressions","i","generated_at","shown_at","NULL");
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT "+impressionId+",i.request_id,i.customer_id,"+primary+","+accessory+",i.rank_position,"+score+",i.strategy,i.model_version,"+generated+" FROM recommendation_impressions i ORDER BY i.rowid")) {
                    while(r.next()) {
                        Double scoreValue=r.getObject(7)==null?null:r.getDouble(7);
                        impressions.add(new RecommendationImpression(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),scoreValue,r.getString(8),r.getString(9),time(r.getString(10))));
                    }
                }
                store.restoreRelationalImpressions(impressions);
            }
            if(loadSupport) {
                List<Warranty> warranties=new ArrayList<>();
                String warrantyStatus=preferredColumn("warranties","w","warranty_status","status","'ACTIVE'");
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT w.serial,w.variant_id,w.order_id,w.customer_id,w.activated_at,w.expires_at,"+warrantyStatus+" FROM warranties w ORDER BY w.rowid")) {
                    while(r.next()) warranties.add(new Warranty(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)),time(r.getString(6)),WarrantyStatus.valueOf(r.getString(7))));
                }
                List<RmaCase> cases=new ArrayList<>();
                String rmaStatus=preferredColumn("rma_cases","r","rma_status","status","'OPEN'");
                try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT r.*,"+rmaStatus+" AS rma_status FROM rma_cases r ORDER BY r.rowid")) {
                    while(r.next()) {
                        RmaCase item=new RmaCase(r.getString("id"),r.getString("serial"),r.getString("customer_id"),r.getString("problem"),time(r.getString("created_at")),r.getString("type"));
                        item.status=r.getString("rma_status"); item.assignedTo=r.getString("assigned_to"); item.diagnosis=r.getString("diagnosis");
                        item.resolutionNotes=r.getString("resolution_notes"); item.replacementParts=r.getString("replacement_parts"); item.updatedAt=time(r.getString("updated_at")); cases.add(item);
                    }
                }
                store.restoreRelationalSupport(warranties,cases);
                store.validateSupportIntegrity();
            }
        } catch(IllegalArgumentException | NullPointerException ex) { throw new IOException("Các hàng SQLite có giá trị không hợp lệ.",ex); }
    }

    public synchronized long save(TechZoneStore store,long expected) throws Exception {
        store.validateInventoryIntegrity(); store.validateSupportIntegrity();
        byte[] bytes=encode(store); db.setAutoCommit(false);
        try {
            long next=expected+1;
            if(expected<0) {
                try(PreparedStatement q=db.prepareStatement("INSERT INTO app_state(id,revision,snapshot) VALUES(1,?,?)")) { q.setLong(1,next); q.setBytes(2,bytes); q.executeUpdate(); }
            } else {
                try(PreparedStatement q=db.prepareStatement("UPDATE app_state SET revision=?,snapshot=? WHERE id=1 AND revision=?")) {
                    q.setLong(1,next); q.setBytes(2,bytes); q.setLong(3,expected);
                    if(q.executeUpdate()!=1) throw new IllegalStateException("Dữ liệu đã thay đổi ở phiên khác; hãy mở lại ứng dụng.");
                }
            }
            writeCoreRows(store); db.commit(); return next;
        } catch(Exception ex) { db.rollback(); throw ex; }
        finally { db.setAutoCommit(true); }
    }
    private void writeCoreRows(TechZoneStore store) throws SQLException {
        boolean hasVariants=hasTable("product_variants"),hasBalances=hasTable("inventory_balances"),hasItems=hasTable("order_items");
        Map<String,String[]> paymentEvidence=new HashMap<>(), shipmentEvidence=new HashMap<>();
        Map<String,String[]> cartTimestamps=new HashMap<>();
        Map<String,Map<String,String[]>> oldCartRows=new HashMap<>();
        record BalanceSnapshot(int available,int reserved,int allocated,int threshold,String warehouse,String updatedAt) { }
        Map<String,BalanceSnapshot> oldBalances=new HashMap<>();
        if(hasColumn("payments","captured_at")) {
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery(
                    "SELECT order_id,captured_at,updated_at,payment_status,payment_method,provider_ref,authorized_at FROM payments")) {
                while(r.next()) paymentEvidence.put(r.getString(1),new String[]{r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7)});
            }
            try(Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT order_id,shipped_at FROM shipments")) {
                while(r.next()) shipmentEvidence.put(r.getString(1),new String[]{r.getString(2)});
            }
        }
        if(hasColumn("shopping_carts","created_at") && hasColumn("shopping_carts","updated_at")) {
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT customer_id,created_at,updated_at,session_key,cart_status FROM shopping_carts")) {
                while(r.next())cartTimestamps.put(r.getString(1),new String[]{r.getString(2),r.getString(3),r.getString(4),r.getString(5)});
            }
        }
        if(hasTable("cart_items")) {
            String oldCartQuery="SELECT customer_id,"+(hasColumn("cart_items","variant_id")?"variant_id":"product_id")+",quantity,"+
                    (hasColumn("cart_items","unit_price_snapshot")?"unit_price_snapshot":"0")+","+
                    (hasColumn("cart_items","added_at")?"added_at":"NULL")+" FROM cart_items";
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery(oldCartQuery)) {
                while(r.next())oldCartRows.computeIfAbsent(r.getString(1),k->new HashMap<>())
                        .put(r.getString(2),new String[]{Integer.toString(r.getInt(3)),Long.toString(r.getLong(4)),r.getString(5)});
            }
        }
        if(hasTable("inventory_balances")) {
            String warehouseColumn=hasColumn("inventory_balances","warehouse_code")?"warehouse_code":"warehouse";
            String updatedColumn=hasColumn("inventory_balances","updated_at")?"updated_at":"NULL";
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,"+warehouseColumn+","+updatedColumn+" FROM inventory_balances")) {
                while(r.next())oldBalances.put(r.getString(1),new BalanceSnapshot(r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getString(6),r.getString(7)));
            }
        }
        try(Statement s=db.createStatement()) {
            s.executeUpdate("DELETE FROM recommendation_impressions");
            if(hasTable("compatibility_reviews"))s.executeUpdate("DELETE FROM compatibility_reviews");
            // Delete dependents before their referenced rows. In particular, warranties
            // point to unit allocations, and RMA cases point to warranties.
            s.executeUpdate("DELETE FROM interaction_events"); s.executeUpdate("DELETE FROM rma_cases"); s.executeUpdate("DELETE FROM warranties");
            s.executeUpdate("DELETE FROM unit_allocations"); s.executeUpdate("DELETE FROM payments"); s.executeUpdate("DELETE FROM shipments");
            s.executeUpdate("DELETE FROM compatibility_rules");
            s.executeUpdate("DELETE FROM cart_items"); s.executeUpdate("DELETE FROM shopping_carts");
            s.executeUpdate("DELETE FROM "+(hasItems?"order_items":"order_lines")); s.executeUpdate("DELETE FROM inventory_units"); s.executeUpdate("DELETE FROM "+(hasBalances?"inventory_balances":"inventory"));
            s.executeUpdate("DELETE FROM product_specs"); s.executeUpdate("DELETE FROM sales_orders");
            if(hasVariants)s.executeUpdate("DELETE FROM product_variants");
            s.executeUpdate("DELETE FROM products");
            s.executeUpdate("DELETE FROM customers");
        }
        boolean customerPhone=hasColumn("customers","phone_number");
        boolean customerDesign=hasColumn("customers","customer_id") && hasColumn("customers","created_at") && hasColumn("customers","updated_at");
        try(PreparedStatement c=db.prepareStatement(customerDesign
                ? "INSERT INTO customers(id,email,name,address,password_salt,password_hash,phone_number,customer_id,full_name,account_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                : customerPhone ? "INSERT INTO customers(id,email,name,address,password_salt,password_hash,phone_number) VALUES(?,?,?,?,?,?,?)"
                : "INSERT INTO customers(id,email,name,address,password_salt,password_hash) VALUES(?,?,?,?,?,?)")) {
            for(TechZoneStore.RelationalAccount account:store.relationalAccounts()) {
                c.setString(1,account.id()); c.setString(2,account.email()); c.setString(3,account.name()); c.setString(4,account.address());
                c.setBytes(5,account.salt()); c.setBytes(6,account.passwordHash());
                if(customerDesign) {
                    c.setString(7,account.phoneNumber());c.setString(8,account.id());c.setString(9,account.name());c.setString(10,"ACTIVE");
                    c.setString(11,time(account.createdAt()));c.setString(12,time(account.updatedAt()));
                } else if(customerPhone)c.setString(7,account.phoneNumber()); c.addBatch();
            }
            c.executeBatch();
        }
        Map<String,Boolean> activeFamilies=new HashMap<>();
        if(hasVariants) try { for(var family:store.productFamilies()) activeFamilies.put(family.id,family.variants().stream().anyMatch(ProductVariant::productActiveForStorage)); }
        catch(IllegalArgumentException ex) { throw new SQLException("Dữ liệu sản phẩm và biến thể không nhất quán.",ex); }
        boolean designBalances=hasBalances && hasColumn("inventory_balances","warehouse_code") && hasColumn("inventory_balances","updated_at");
        String inventoryInsert=designBalances?"INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse,warehouse_code,updated_at) VALUES(?,?,?,?,?,?,?,?)"
                :hasBalances?"INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse) VALUES(?,?,?,?,?,?)"
                :"INSERT INTO inventory(product_id,available,reserved,allocated,threshold,warehouse) VALUES(?,?,?,?,?,?)";
        boolean designProducts=hasColumn("products","product_id");
        boolean designVariants=hasVariants && hasColumn("product_variants","variant_id");
        String productInsert=designProducts
                ? "INSERT INTO products(id,sku,name,category,brand,price,sale_price,active,description,color,warranty_months,product_id,product_name,brand_name,image_url,created_at,is_active) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO products(id,sku,name,category,brand,price,sale_price,active,description,color,warranty_months) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        String variantInsert=designVariants
                ? "INSERT INTO product_variants(id,product_id,sku,variant_name,color,price,sale_price,warranty_months,active,variant_id,capacity,is_active,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO product_variants(id,product_id,sku,variant_name,color,price,sale_price,warranty_months,active) VALUES(?,?,?,?,?,?,?,?,?)";
        try(PreparedStatement p=db.prepareStatement(productInsert);
            PreparedStatement vp=hasVariants?db.prepareStatement(variantInsert):null;
            PreparedStatement sp=db.prepareStatement("INSERT INTO product_specs VALUES(?,?,?)");
            PreparedStatement ib=db.prepareStatement(inventoryInsert);
            PreparedStatement u=db.prepareStatement("INSERT INTO inventory_units(serial,product_id,status,order_id,unit_id,variant_id,serial_imei,batch_number,received_at,warehouse_location,unit_status) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            Set<String> writtenFamilies=new HashSet<>();
            for(ProductVariant product:store.products()) {
                if(!hasVariants || writtenFamilies.add(product.productId())) {
                    p.setString(1,hasVariants?product.productId():product.id); p.setString(2,product.sku); p.setString(3,hasVariants?product.productName():product.name); p.setString(4,product.category); p.setString(5,product.brand);
                    p.setLong(6,product.price); p.setLong(7,product.salePrice); p.setInt(8,(hasVariants?activeFamilies.get(product.productId()):product.active)?1:0); p.setString(9,product.description); p.setString(10,product.color); p.setInt(11,product.warrantyMonths);
                    if(designProducts) {
                        p.setString(12,product.productId());p.setString(13,product.productName());p.setString(14,product.brand);
                        p.setString(15,"/v1/products/"+product.productId()+"/image");p.setString(16,time(product.createdAt));
                        p.setInt(17,(hasVariants?activeFamilies.get(product.productId()):product.active)?1:0);
                    }
                    p.executeUpdate();
                }
                if(vp!=null) {
                    vp.setString(1,product.id);vp.setString(2,product.productId());vp.setString(3,product.sku);vp.setString(4,product.name);
                    vp.setString(5,product.color);vp.setLong(6,product.price);vp.setLong(7,product.salePrice);vp.setInt(8,product.warrantyMonths);vp.setInt(9,product.variantActiveForStorage()?1:0);
                    if(designVariants) {
                        vp.setString(10,product.id);vp.setString(11,product.specs.getOrDefault("capacity",product.specs.getOrDefault("storage","")));
                        vp.setInt(12,product.variantActiveForStorage()?1:0);vp.setString(13,time(product.updatedAt));
                    }
                    vp.executeUpdate();
                }
                for(var entry:product.specs.entrySet()) { sp.setString(1,product.id); sp.setString(2,entry.getKey()); sp.setString(3,entry.getValue()); sp.addBatch(); }
                InventoryBalance b=store.balance(product.id); ib.setString(1,product.id); ib.setInt(2,b.available); ib.setInt(3,b.reserved); ib.setInt(4,b.allocated); ib.setInt(5,b.reorderThreshold); ib.setString(6,b.warehouse);
                if(designBalances) {
                    BalanceSnapshot old=oldBalances.get(product.id);
                    boolean changed=old==null || old.available()!=b.available || old.reserved()!=b.reserved || old.allocated()!=b.allocated
                            || old.threshold()!=b.reorderThreshold || !Objects.equals(old.warehouse(),b.warehouse);
                    String updatedAt=changed || old.updatedAt()==null?time(LocalDateTime.now()):old.updatedAt();
                    ib.setString(7,b.warehouse);ib.setString(8,updatedAt);
                }
                ib.addBatch();
                for(InventoryUnit unit:store.unitsForVariant(product.id)) {
                    u.setString(1,unit.serial);u.setString(2,product.id);u.setString(3,unit.status.name());u.setString(4,unit.orderId);
                    u.setString(5,unit.serial);u.setString(6,product.id);u.setString(7,unit.serial);u.setString(8,unit.batchNumber);
                    u.setString(9,time(unit.receivedAt));u.setString(10,unit.warehouseLocation);u.setString(11,unit.status.name());u.addBatch();
                }
            }
            sp.executeBatch(); ib.executeBatch(); u.executeBatch();
        }
        boolean designCartItems=hasColumn("cart_items","unit_price_snapshot") && hasColumn("shopping_carts","cart_id");
        boolean designCartTimes=designCartItems && hasColumn("shopping_carts","created_at") && hasColumn("shopping_carts","updated_at") && hasColumn("cart_items","added_at");
        Map<String,LocalDateTime> accountCreatedAt=new HashMap<>();
        for(TechZoneStore.RelationalAccount account:store.relationalAccounts())accountCreatedAt.put(account.id(),account.createdAt());
        Map<String,List<CartItem>> currentCarts=store.relationalCarts();
        String cartInsert=designCartTimes
                    ? "INSERT INTO shopping_carts(customer_id,cart_id,session_key,cart_status,created_at,updated_at) VALUES(?,?,?,?,?,?)"
                    : designCartItems
                        ? "INSERT INTO shopping_carts(customer_id,cart_id,session_key,cart_status) VALUES(?,?,?,'OPEN')"
                        : "INSERT INTO shopping_carts(customer_id) VALUES(?)";
        String cartItemInsert=designCartTimes
                    ? "INSERT INTO cart_items(customer_id,product_id,quantity,cart_id,variant_id,unit_price_snapshot,added_at) VALUES(?,?,?,?,?,?,?)"
                    : designCartItems
                        ? "INSERT INTO cart_items(customer_id,product_id,quantity,cart_id,variant_id,unit_price_snapshot) VALUES(?,?,?,?,?,?)"
                        : "INSERT INTO cart_items(customer_id,product_id,quantity) VALUES(?,?,?)";
        try(PreparedStatement c=db.prepareStatement(cartInsert);
            PreparedStatement i=db.prepareStatement(cartItemInsert)) {
            for(var cart:currentCarts.entrySet()) {
                List<CartItem> oldItems=new ArrayList<>();
                Map<String,String[]> oldRows=oldCartRows.getOrDefault(cart.getKey(),Map.of());
                for(var old:oldRows.entrySet())oldItems.add(new CartItem(old.getKey(),Integer.parseInt(old.getValue()[0]),Long.parseLong(old.getValue()[1])));
                boolean changed=cartChanged(oldItems,cart.getValue());
                String[] oldTimes=cartTimestamps.get(cart.getKey());
                LocalDateTime now=LocalDateTime.now();
                LocalDateTime firstItem=cart.getValue().stream().map(item->item.addedAt).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
                LocalDateTime created=oldTimes==null?firstItem:time(oldTimes[0]);
                if(created==null)created=accountCreatedAt.getOrDefault(cart.getKey(),now);
                LocalDateTime updated=oldTimes==null || changed?now:time(oldTimes[1]);
                if(updated==null)updated=created;
                c.setString(1,cart.getKey());
                if(designCartItems) {
                    c.setString(2,cart.getKey());c.setString(3,oldTimes!=null && oldTimes[2]!=null?oldTimes[2]:"CUSTOMER:"+cart.getKey());
                    c.setString(4,oldTimes!=null && oldTimes[3]!=null?oldTimes[3]:"OPEN");
                    if(designCartTimes) { c.setString(5,time(created));c.setString(6,time(updated)); }
                }
                c.executeUpdate();
                for(CartItem item:cart.getValue()) {
                    i.setString(1,cart.getKey()); i.setString(2,item.variantId); i.setInt(3,item.quantity);
                    if(designCartItems) {
                        i.setString(4,cart.getKey());i.setString(5,item.variantId);
                        i.setLong(6,item.unitPriceSnapshot>0?item.unitPriceSnapshot:store.product(item.variantId).getEffectivePrice());
                        if(designCartTimes) {
                            String[] oldItem=oldRows.get(item.variantId);
                            String added= item.addedAt!=null?time(item.addedAt):oldItem==null?time(now):oldItem[2];
                            i.setString(7,added==null?time(now):added);
                        }
                    }
                    i.addBatch();
                }
            }
            i.executeBatch();
        }
        boolean eventReferences=hasColumn("interaction_events","customer_id") && hasColumn("interaction_events","variant_id");
        boolean eventDesign=eventReferences && hasColumn("interaction_events","request_id") && hasColumn("interaction_events","event_source");
        String eventInsert=eventDesign
                ? "INSERT INTO interaction_events(seq,event_type,detail,occurred_at,customer_id,variant_id,anonymous_session_key,request_id,event_source,consent_scope,search_query,filters,sort_by,page,event_context) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                : eventReferences?"INSERT INTO interaction_events(seq,event_type,detail,occurred_at,customer_id,variant_id) VALUES(?,?,?,?,?,?)"
                : "INSERT INTO interaction_events(seq,event_type,detail,occurred_at) VALUES(?,?,?,?)";
        try(PreparedStatement p=db.prepareStatement("INSERT INTO compatibility_rules(left_variant_id,right_variant_id) VALUES(?,?)");
            PreparedStatement event=db.prepareStatement(eventInsert)) {
            for(TechZoneStore.RelationalPair pair:store.relationalCompatibilityPairs()) {
                p.setString(1,pair.left()); p.setString(2,pair.right()); p.addBatch();
            }
            p.executeBatch(); int seq=0;
            for(InteractionEvent item:store.interactionEvents()) {
                event.setInt(1,++seq); event.setString(2,item.type); event.setString(3,item.detail); event.setString(4,time(item.occurredAt));
                if(eventReferences) { event.setString(5,item.customerId);event.setString(6,item.variantId); }
                if(eventDesign) {
                    event.setString(7,item.anonymousSessionKey);event.setString(8,item.requestId);
                    event.setString(9,item.eventSource==null?"LEGACY_IMPORT":item.eventSource);
                    event.setString(10,item.consentScope==null?(item.customerId==null?"UNKNOWN":"PERSONALIZATION"):item.consentScope);
                    event.setString(11,item.searchQuery);event.setString(12,item.filters);event.setString(13,item.sortBy);
                    if(item.page==null)event.setNull(14,Types.INTEGER);else event.setInt(14,item.page);
                    event.setString(15,item.eventContext==null?item.detail:item.eventContext);
                }
                event.addBatch();
            }
            event.executeBatch();
        }
        if(hasTable("compatibility_reviews"))writeCompatibilityReviews(store);
        try(PreparedStatement p=db.prepareStatement("INSERT INTO recommendation_impressions(id,request_id,customer_id,primary_id,accessory_id,rank_position,score,strategy,model_version,shown_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for(RecommendationImpression item:store.recommendationImpressions()) {
                p.setString(1,item.id); p.setString(2,item.requestId); p.setString(3,item.customerId); p.setString(4,item.primaryId); p.setString(5,item.accessoryId);
                p.setInt(6,item.rank); if(item.score==null)p.setNull(7,Types.REAL);else p.setDouble(7,item.score);
                p.setString(8,item.strategy); p.setString(9,item.modelVersion); p.setString(10,time(item.shownAt)); p.addBatch();
            }
            p.executeBatch();
        }
        String sql="INSERT INTO sales_orders(id,number,customer_id,status,total,created_at,customer_name,shipping_address,payment_method,idempotency_key,promotion_code,subtotal,discount,tax,shipping_fee,payment_reference,tracking_code,carrier,package_weight_grams,package_dimensions,sales_verified_by,sales_verified_at,pending_reason,paid_at,delivered_at,reconciliation_due_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String orderItemInsert=hasItems?"INSERT INTO order_items(order_id,variant_id,quantity,unit_price,reserved_qty,allocated_qty) VALUES(?,?,?,?,?,?)":"INSERT INTO order_lines(order_id,product_id,quantity,unit_price,reserved_qty,allocated_qty) VALUES(?,?,?,?,?,?)";
        try(PreparedStatement o=db.prepareStatement(sql); PreparedStatement l=db.prepareStatement(orderItemInsert)) {
            for(SalesOrder order:store.orders()) {
                o.setString(1,order.id); o.setString(2,order.number); o.setString(3,order.customerId); o.setString(4,order.status.name()); o.setLong(5,order.total); o.setString(6,time(order.createdAt));
                o.setString(7,order.customerName); o.setString(8,order.shippingAddress); o.setString(9,order.paymentMethod); o.setString(10,order.idempotencyKey); o.setString(11,order.promotionCode);
                o.setLong(12,order.subtotal); o.setLong(13,order.discount); o.setLong(14,order.tax); o.setLong(15,order.shippingFee); o.setString(16,order.paymentReference);
                o.setString(17,order.trackingCode); o.setString(18,order.carrier); o.setInt(19,order.packageWeightGrams); o.setString(20,order.packageDimensions);
                o.setString(21,order.salesVerifiedBy); o.setString(22,time(order.salesVerifiedAt)); o.setString(23,order.pendingReason); o.setString(24,time(order.paidAt));
                o.setString(25,time(order.deliveredAt)); o.setString(26,time(order.reconciliationDueAt)); o.executeUpdate();
                for(OrderLine line:order.lines) { l.setString(1,order.id); l.setString(2,line.variantId); l.setInt(3,line.quantity); l.setLong(4,line.unitPrice); l.setInt(5,line.reservedQuantity); l.setInt(6,line.allocatedQuantity); l.addBatch(); }
            }
            l.executeBatch();
        }
        if(hasTable("order_items") && hasColumn("order_items","discount_amount")) {
            try(PreparedStatement line=db.prepareStatement("UPDATE order_items SET discount_amount=?,line_total=?,reservation_state=? WHERE order_id=? AND variant_id=?")) {
                for(SalesOrder order:store.orders()) for(OrderLine item:order.lines) {
                    line.setLong(1,item.discountAmount);line.setLong(2,item.lineTotal());
                    line.setString(3,item.reservedQuantity>0?"RESERVED":item.allocatedQuantity>=item.quantity?"ALLOCATED":"OPEN");
                    line.setString(4,order.id);line.setString(5,item.variantId);line.addBatch();
                }
                line.executeBatch();
            }
        }
        boolean allocationEvidence=hasColumn("unit_allocations","allocated_by");
        String allocationInsert=allocationEvidence
                ? "INSERT INTO unit_allocations(serial,order_id,product_id,allocated_by,scanned_at) VALUES(?,?,?,?,?)"
                : "INSERT INTO unit_allocations(serial,order_id,product_id) VALUES(?,?,?)";
        try(PreparedStatement p=db.prepareStatement("INSERT INTO payments(order_id,method,provider_ref,payment_status,authorized_at) VALUES(?,?,?,?,?)");
            PreparedStatement shipment=db.prepareStatement("INSERT INTO shipments(order_id,tracking_code,carrier,package_weight_grams,package_dimensions) VALUES(?,?,?,?,?)");
            PreparedStatement allocation=db.prepareStatement(allocationInsert)) {
            for(SalesOrder order:store.orders()) {
                String paymentStatus=!order.paymentReference.isBlank()?"AUTHORIZED":order.status==OrderStatus.CANCELLED?"DECLINED":"UNKNOWN";
                p.setString(1,order.id); p.setString(2,order.paymentMethod); p.setString(3,order.paymentReference); p.setString(4,paymentStatus); p.setString(5,time(order.paidAt)); p.addBatch();
                if(order.trackingCode!=null && !order.trackingCode.isBlank()) {
                    shipment.setString(1,order.id); shipment.setString(2,order.trackingCode); shipment.setString(3,order.carrier);
                    shipment.setInt(4,order.packageWeightGrams); shipment.setString(5,order.packageDimensions); shipment.addBatch();
                }
                for(OrderLine line:order.lines) for(String serial:line.serials) {
                    allocation.setString(1,serial); allocation.setString(2,order.id); allocation.setString(3,line.variantId);
                    if(allocationEvidence) {
                        UnitAllocation evidence=line.allocationDetails(serial);
                        allocation.setString(4,evidence==null?"SYSTEM_IMPORT":Objects.toString(evidence.allocatedBy(),"SYSTEM_IMPORT"));
                        allocation.setString(5,time(evidence==null?order.createdAt:evidence.scannedAt()));
                    }
                    allocation.addBatch();
                }
            }
            p.executeBatch(); shipment.executeBatch(); allocation.executeBatch();
        }
        if(hasColumn("payments","captured_at")) {
            try(PreparedStatement p=db.prepareStatement("UPDATE payments SET captured_at=?,updated_at=? WHERE order_id=?")) {
                LocalDateTime recordedAt=LocalDateTime.now();
                for(SalesOrder order:store.orders()) {
                    String status=!order.paymentReference.isBlank()?"AUTHORIZED":order.status==OrderStatus.CANCELLED?"DECLINED":"UNKNOWN";
                    String[] old=paymentEvidence.get(order.id);
                    boolean changed=old==null || !Objects.equals(old[2],status) || !Objects.equals(old[3],order.paymentMethod)
                            || !Objects.equals(old[4],order.paymentReference) || !Objects.equals(old[5],time(order.paidAt));
                    String capturedAt=old==null?time(order.paidAt!=null?order.paidAt:order.createdAt):old[0];
                    if(capturedAt==null)capturedAt=UNRECORDED_TIME_VALUE;
                    String updatedAt=old!=null && !changed && old[1]!=null?old[1]
                            :time(order.paidAt!=null?order.paidAt:recordedAt);
                    p.setString(1,capturedAt);p.setString(2,updatedAt);p.setString(3,order.id);p.addBatch();
                }
                p.executeBatch();
            }
        }
        if(hasColumn("shipments","shipped_at")) {
            try(PreparedStatement p=db.prepareStatement("UPDATE shipments SET shipped_at=? WHERE order_id=?")) {
                for(SalesOrder order:store.orders()) if(order.trackingCode!=null && !order.trackingCode.isBlank()) {
                    String[] old=shipmentEvidence.get(order.id);
                    String shippedAt=time(order.shippedAt);
                    if(shippedAt==null && old!=null)shippedAt=old[0];
                    p.setString(1,shippedAt);p.setString(2,order.id);p.addBatch();
                }
                p.executeBatch();
            }
        }
        try(PreparedStatement w=db.prepareStatement("INSERT INTO warranties(serial,variant_id,order_id,customer_id,activated_at,expires_at,status) VALUES(?,?,?,?,?,?,?)");
            PreparedStatement r=db.prepareStatement("INSERT INTO rma_cases(id,serial,customer_id,problem,created_at,type,status,assigned_to,diagnosis,resolution_notes,replacement_parts,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(Warranty warranty:store.warranties()) {
                w.setString(1,warranty.serial); w.setString(2,warranty.variantId); w.setString(3,warranty.orderId); w.setString(4,warranty.customerId);
                w.setString(5,time(warranty.activatedAt)); w.setString(6,time(warranty.expiresAt)); w.setString(7,warranty.status.name()); w.addBatch();
            }
            w.executeBatch();
            for(RmaCase item:store.rmas()) {
                r.setString(1,item.id); r.setString(2,item.serial); r.setString(3,item.customerId); r.setString(4,item.problem); r.setString(5,time(item.createdAt));
                r.setString(6,item.type); r.setString(7,item.status); r.setString(8,item.assignedTo); r.setString(9,item.diagnosis);
                r.setString(10,item.resolutionNotes); r.setString(11,item.replacementParts); r.setString(12,time(item.updatedAt)); r.addBatch();
            }
            r.executeBatch();
        }
        // Refresh aliases after inserting the complete relational snapshot. Running this
        // only before DELETE left newly written rows with NULL/stale design fields.
        if(hasColumn("products","product_id") && hasTable("product_variants") && hasColumn("product_variants","variant_id"))
            DesignFieldMigration.refresh(db);
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
