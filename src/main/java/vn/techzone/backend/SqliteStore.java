package vn.techzone.backend;

import vn.techzone.application.TechZoneStore;
import vn.techzone.domain.Payment;
import vn.techzone.domain.Shipment;
import vn.techzone.domain.UnitAllocation;
import vn.techzone.domain.TechZoneModels.*;
import vn.techzone.infrastructure.DemoStoreRepository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/** Relational repository. Java serialization is used only for localhost transport, never for database storage. */
public final class SqliteStore implements AutoCloseable {
    private static final LocalDateTime UNRECORDED=LocalDateTime.of(1900,1,1,0,0,1);
    private final Connection db;
    private final Path dbPath;
    public record State(long revision,byte[] bytes) { }
    public record MarketOffer(String productId,String sourceSku,String url,long price,String availability,String fetchedAt) { }
    private record PaymentEvidence(String orderId,String providerRef,String status,long amount,String capturedAt,String updatedAt) { }

    public static boolean hasState(Path path) throws Exception {
        if(!Files.exists(path))return false;
        Class.forName("org.sqlite.JDBC");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());Statement s=c.createStatement()) {
            if(!tableExists(c,"app_metadata") || !tableExists(c,"products"))return false;
            try(ResultSet r=s.executeQuery("SELECT 1 FROM app_metadata WHERE id=1 AND EXISTS(SELECT 1 FROM products)")) { return r.next(); }
        }
    }

    public SqliteStore(Path path,TechZoneStore initial) throws Exception {
        Class.forName("org.sqlite.JDBC");dbPath=path.toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());
        if(Files.exists(dbPath) && hasTableAtPath(dbPath,"app_state")) migrateLegacyDatabase(dbPath,initial);
        db=DriverManager.getConnection("jdbc:sqlite:"+dbPath);
        try(Statement s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000"); }
        RelationalSchema.create(db);
        if(!hasState(dbPath))save(initial,-1);
    }

    public Path databasePath(){return dbPath;}

    private static void migrateLegacyDatabase(Path path,TechZoneStore initial) throws Exception {
        Path absolute=path.toAbsolutePath().normalize(),parent=absolute.getParent();
        Path backup=absolute.resolveSibling(absolute.getFileName()+".pre-normalize-"+System.currentTimeMillis()+".bak");
        Path temp=absolute.resolveSibling(absolute.getFileName()+".normalized-"+UUID.randomUUID()+".tmp");
        Path legacyDir=null;
        TechZoneStore state=null;
        Files.copy(absolute,backup,StandardCopyOption.COPY_ATTRIBUTES);
        try {
            legacyDir=Files.createTempDirectory(parent,".techzone-legacy-import-");
            Path legacyPath=legacyDir.resolve("legacy.sqlite");
            Files.copy(backup,legacyPath,StandardCopyOption.COPY_ATTRIBUTES);
            long revision;
            List<LegacySqliteStore.MarketOffer> offers;
            try(LegacySqliteStore legacy=new LegacySqliteStore(legacyPath,initial)) {
                LegacySqliteStore.State old=legacy.read();
                if(old==null) { legacy.save(initial,-1);old=legacy.read(); }
                if(old==null)throw new IOException("Không tải được trạng thái từ cơ sở dữ liệu cũ.");
                revision=old.revision();state=LegacySqliteStore.decodeSession(old.bytes());offers=legacy.marketOffers();
            }
            try(Connection target=DriverManager.getConnection("jdbc:sqlite:"+temp)) {
                try(Statement s=target.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
                RelationalSchema.create(target);target.setAutoCommit(false);
                try {
                    writeRows(target,state);
                    writeMarketOffers(target,offers.stream().map(o->new MarketOffer(o.productId(),o.sourceSku(),
                            o.url(),o.price(),o.availability(),o.fetchedAt())).toList());
                    try(PreparedStatement p=target.prepareStatement("UPDATE app_metadata SET revision=?,active_customer_id=? WHERE id=1")) {
                        p.setLong(1,revision);p.setString(2,storeCustomer(state));p.executeUpdate();
                    }
                    target.commit();
                    try(Statement s=target.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) {
                        if(r.next())throw new SQLException("Tham chiếu lỗi trong quá trình chuẩn hóa: "+r.getString(1));
                    }
                } catch(Exception ex) { target.rollback();throw ex; }
                finally { target.setAutoCommit(true); }
            }
            try { Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException ex) { Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            if(state!=null)state.closeRepository();
            Files.deleteIfExists(temp);
            if(legacyDir!=null)deleteLegacyImportDirectory(legacyDir,parent);
        }
    }

    private static void deleteLegacyImportDirectory(Path directory,Path expectedParent) {
        Path root=directory.toAbsolutePath().normalize(),parent=expectedParent.toAbsolutePath().normalize();
        if(!root.startsWith(parent) || !root.getFileName().toString().startsWith(".techzone-legacy-import-"))
            throw new IllegalStateException("Unexpected legacy-import temporary directory");
        try(var paths=Files.walk(root)) {
            for(Path item:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);
        } catch(IOException ignored) { /* A failed cleanup leaves only the isolated temporary import copy. */ }
    }

    private static String storeCustomer(TechZoneStore store){return Objects.toString(store.signedInCustomerId(),TechZoneStore.CUSTOMER_ID);}
    private static boolean hasTableAtPath(Path path,String name) throws SQLException {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath())) { return tableExists(c,name); }
    }
    private static boolean tableExists(Connection c,String name) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            p.setString(1,name);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }

    public synchronized List<MarketOffer> marketOffers() throws SQLException {
        List<MarketOffer> result=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT variant_id,source_sku,source_url,price,availability,fetched_at FROM market_offers ORDER BY variant_id")) {
            while(r.next())result.add(new MarketOffer(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6)));
        }
        return result;
    }

    public synchronized void importRealCatalog() throws Exception { runSeed("catalog-real-v1",TechZoneStore::importRealCatalog); }
    public synchronized int importBundledGearVNCatalog() throws Exception {
        if(seedApplied("gearvn-catalog-snapshot-v1")) return 0;
        try(InputStream input=SqliteStore.class.getResourceAsStream("/vn/techzone/catalog/gearvn-catalog.tsv")) {
            if(input==null) throw new IOException("Thiếu ảnh chụp danh mục crawl GearVN đi kèm dự án.");
            Path temporary=Files.createTempFile(dbPath.getParent(),".gearvn-catalog-",".tsv");
            try {
                Files.copy(input,temporary,StandardCopyOption.REPLACE_EXISTING);
                int count=syncGearVNCatalog(temporary);
                try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO seed_migrations(name,applied_at) VALUES(?,?)")) {
                    p.setString(1,"gearvn-catalog-snapshot-v1");p.setString(2,LocalDateTime.now().toString());p.executeUpdate();
                }
                return count;
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    public synchronized int refreshGearVNCatalog(Path snapshot) throws Exception { return syncGearVNCatalog(snapshot); }
    public synchronized void importBundledGearVNDetails() throws Exception {
        if(seedApplied("gearvn-quality-snapshot-v3")) return;
        try(InputStream input=SqliteStore.class.getResourceAsStream("/vn/techzone/catalog/gearvn-catalog.tsv")) {
            if(input==null) return;
            Path temporary=Files.createTempFile(dbPath.getParent(),".gearvn-detail-",".tsv");
            try {
                Files.copy(input,temporary,StandardCopyOption.REPLACE_EXISTING);
                List<TechZoneStore.GearVNCatalogProduct> listings=readGearVNCatalog(temporary);
                if(listings.stream().noneMatch(item->item.specs().containsKey("sourceSpecStatus"))) return;
                runSeed("gearvn-quality-snapshot-v3",store->store.mergeGearVNDetails(listings));
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    private boolean seedApplied(String name) throws SQLException {
        try(PreparedStatement p=db.prepareStatement("SELECT 1 FROM seed_migrations WHERE name=?")) {
            p.setString(1,name);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    private int syncGearVNCatalog(Path snapshot) throws Exception {
        List<TechZoneStore.GearVNCatalogProduct> listings=readGearVNCatalog(snapshot);
        State old=read();TechZoneStore candidate=decode(old.bytes());
        try {
            candidate.syncGearVNCatalog(listings);
            Map<String,MarketOffer> offers=new LinkedHashMap<>();
            for(MarketOffer offer:marketOffers()) offers.put(offer.productId(),offer);
            for(var listing:listings) offers.put(listing.id(),new MarketOffer(listing.id(),listing.sourceSku(),
                    listing.sourceUrl(),listing.salePrice()>0?listing.salePrice():listing.price(),
                    listing.availability(),Objects.toString(listing.specs().get("sourceFetchedAt"),"")));
            save(candidate,old.revision(),new ArrayList<>(offers.values()));
            return listings.size();
        } finally { candidate.closeRepository(); }
    }
    private static List<TechZoneStore.GearVNCatalogProduct> readGearVNCatalog(Path snapshot) throws IOException {
        List<TechZoneStore.GearVNCatalogProduct> result=new ArrayList<>();
        try(BufferedReader reader=Files.newBufferedReader(snapshot,StandardCharsets.US_ASCII)) {
            for(String line; (line=reader.readLine())!=null;) {
                if(line.isBlank()) continue;
                String[] encoded=line.split("\\t",-1);
                if(encoded.length<26 || (encoded.length-12)%2!=0)
                    throw new IOException("GearVN crawl TSV has an invalid row.");
                String[] f=new String[encoded.length];
                try { for(int i=0;i<encoded.length;i++) f[i]=new String(Base64.getDecoder().decode(encoded[i]),StandardCharsets.UTF_8); }
                catch(IllegalArgumentException ex) { throw new IOException("GearVN crawl TSV contains invalid Base64.",ex); }
                Map<String,String> specs=new LinkedHashMap<>();
                for(int i=12;i<f.length;i+=2) if(specs.putIfAbsent(f[i],f[i+1])!=null)
                    throw new IOException("GearVN crawl TSV contains duplicate specification keys.");
                LocalDateTime fetchedAt;
                try { fetchedAt=OffsetDateTime.parse(f[11]).toLocalDateTime(); }
                catch(RuntimeException ex) { try { fetchedAt=LocalDateTime.parse(f[11]); }
                    catch(RuntimeException invalid) { throw new IOException("GearVN crawl timestamp is invalid.",invalid); } }
                long price,salePrice;
                try { price=Long.parseLong(f[5]);salePrice=Long.parseLong(f[6]); }
                catch(NumberFormatException ex) { throw new IOException("GearVN crawl price is invalid.",ex); }
                result.add(new TechZoneStore.GearVNCatalogProduct(f[0],f[1],f[2],f[3],f[4],price,salePrice,
                        f[7],f[8],f[9],f[10],fetchedAt,specs));
            }
        }
        if(result.size()<50 || result.size()>500) throw new IOException("GearVN crawl snapshot is incomplete; existing data was kept.");
        return List.copyOf(result);
    }
    public synchronized void importVerifiedManufacturerPair() throws Exception { runSeed("verified-pair-am5-v1",s->s.seedVerifiedManufacturerPair()?1:0); }
    public synchronized void importVerifiedRyzen7600Pairs() throws Exception { runSeed("verified-pair-7600-v1",s->s.seedVerifiedRyzen7600Pairs()?1:0); }
    public synchronized void importVerifiedStorageFacts() throws Exception { runSeed("verified-m2-facts-v1",s->s.seedVerifiedStorageFacts()?1:0); }
    public synchronized void seedDemoStock100() throws Exception { runSeed("demo-stock-100-v1",TechZoneStore::seedDemoStock100); }
    @FunctionalInterface private interface Seed { int apply(TechZoneStore store) throws Exception; }
    private void runSeed(String name,Seed seed) throws Exception {
        try(PreparedStatement q=db.prepareStatement("SELECT 1 FROM seed_migrations WHERE name=?")) {
            q.setString(1,name);try(ResultSet r=q.executeQuery()){if(r.next())return;}
        }
        State old=read();TechZoneStore store=decode(old.bytes());
        try {
            seed.apply(store);save(store,old.revision());
            try(PreparedStatement p=db.prepareStatement("INSERT OR IGNORE INTO seed_migrations(name,applied_at) VALUES(?,?)")) {
                p.setString(1,name);p.setString(2,LocalDateTime.now().toString());p.executeUpdate();
            }
        } finally { store.closeRepository(); }
    }

    public static byte[] encode(TechZoneStore store) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(bytes)){out.writeObject(store);}
        return bytes.toByteArray();
    }
    public static TechZoneStore decode(byte[] bytes) throws IOException { return decode(bytes,false); }
    public static TechZoneStore decodeSession(byte[] bytes) throws IOException { return decode(bytes,true); }
    private static TechZoneStore decode(byte[] bytes,boolean preserveSession) throws IOException {
        TechZoneStore.preserveDeserializedSession(preserveSession);
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            DemoStoreRepository.installFilter(in);Object value=in.readObject();
            if(!(value instanceof TechZoneStore store))throw new IOException("Dữ liệu không thuộc TechZone.");return store;
        } catch(ClassNotFoundException|RuntimeException ex){throw new IOException("Không đọc được trạng thái TechZone.",ex);}
        finally {TechZoneStore.preserveDeserializedSession(false);}
    }

    public synchronized State read() throws SQLException,IOException {
        long revision;String active;
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT revision,active_customer_id FROM app_metadata WHERE id=1")) {
            if(!r.next())return null;revision=r.getLong(1);active=r.getString(2);
        }
        TechZoneStore store=new TechZoneStore();
        try {loadRows(store,active);return new State(revision,encode(store));}
        catch(Exception ex){store.closeRepository();if(ex instanceof IOException io)throw io;throw new IOException("Không đọc được dữ liệu quan hệ TechZone.",ex);}
    }

    public synchronized long save(TechZoneStore store,long expected) throws Exception {
        return save(store,expected,marketOffers());
    }
    private synchronized long save(TechZoneStore store,long expected,List<MarketOffer> offers) throws Exception {
        store.validateInventoryIntegrity();store.validateSupportIntegrity();
        db.setAutoCommit(false);
        try {
            List<PaymentEvidence> evidence=new ArrayList<>();
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT order_id,provider_ref,payment_status,amount,captured_at,updated_at FROM payments")) {
                while(r.next()) evidence.add(new PaymentEvidence(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getString(6)));
            }
            long next=expected+1;
            try(PreparedStatement p=db.prepareStatement("UPDATE app_metadata SET revision=?,active_customer_id=? WHERE id=1 AND revision=?")) {
                p.setLong(1,next);p.setString(2,storeCustomer(store));p.setLong(3,expected<0?0:expected);
                if(p.executeUpdate()!=1)throw new IllegalStateException("Dữ liệu đã thay đổi ở phiên khác; hãy tải lại ứng dụng.");
            }
            writeRows(db,store);writeMarketOffers(db,offers);
            try(PreparedStatement p=db.prepareStatement("UPDATE payments SET captured_at=?,updated_at=? WHERE order_id=? AND provider_ref=? AND payment_status=? AND amount=?")) {
                for(PaymentEvidence e:evidence) {
                    p.setString(1,e.capturedAt());p.setString(2,e.updatedAt());p.setString(3,e.orderId());
                    p.setString(4,e.providerRef());p.setString(5,e.status());p.setLong(6,e.amount());p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();return next;
        } catch(Exception ex){db.rollback();throw ex;}
        finally {db.setAutoCommit(true);}
    }

    private static String time(LocalDateTime value){return value==null?UNRECORDED.toString():value.toString();}
    private static LocalDateTime time(String value){
        if(value==null||value.isBlank())return null;LocalDateTime parsed=LocalDateTime.parse(value);return UNRECORDED.equals(parsed)?null:parsed;
    }
    private static String json(Map<String,String> values) {
        StringBuilder out=new StringBuilder("{");boolean first=true;
        for(var entry:values.entrySet()){if(!first)out.append(',');first=false;out.append(jsonString(entry.getKey())).append(':').append(jsonString(entry.getValue()));}
        return out.append('}').toString();
    }
    private static String jsonString(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(char ch:Objects.toString(value,"").toCharArray())switch(ch){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(ch<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)ch));else out.append(ch);}}
        return out.append('"').toString();
    }
    private static Map<String,String> parseJson(String json) throws IOException {
        Map<String,String> result=new LinkedHashMap<>();int[] at={0};skip(json,at);expect(json,at,'{');skip(json,at);
        if(peek(json,at)=='}'){at[0]++;return result;}
        while(true){String key=parseJsonString(json,at);skip(json,at);expect(json,at,':');skip(json,at);String value=parseJsonString(json,at);result.put(key,value);skip(json,at);
            char next=peek(json,at);if(next=='}'){at[0]++;break;}expect(json,at,',');skip(json,at);}
        return result;
    }
    private static String parseJsonString(String s,int[] at) throws IOException {
        expect(s,at,'"');StringBuilder out=new StringBuilder();
        while(at[0]<s.length()){char ch=s.charAt(at[0]++);if(ch=='"')return out.toString();if(ch!='\\'){out.append(ch);continue;}
            if(at[0]>=s.length())throw new IOException("Invalid product technical_specs JSON.");char e=s.charAt(at[0]++);
            switch(e){case '"','\\','/'->out.append(e);case 'b'->out.append('\b');case 'f'->out.append('\f');case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');case 'u'->{if(at[0]+4>s.length())throw new IOException("Invalid JSON Unicode escape.");out.append((char)Integer.parseInt(s.substring(at[0],at[0]+4),16));at[0]+=4;}default->throw new IOException("Invalid JSON escape.");}}
        throw new IOException("Unterminated JSON string.");
    }
    private static void skip(String s,int[] at){while(at[0]<s.length()&&Character.isWhitespace(s.charAt(at[0])))at[0]++;}
    private static char peek(String s,int[] at) throws IOException {if(at[0]>=s.length())throw new IOException("Unexpected end of JSON.");return s.charAt(at[0]);}
    private static void expect(String s,int[] at,char value) throws IOException {if(peek(s,at)!=value)throw new IOException("Invalid JSON structure.");at[0]++;}

    private static void writeRows(Connection c,TechZoneStore store) throws Exception {
        try(Statement s=c.createStatement()) {
            for(String table:List.of("provider_callbacks","recommendation_attributions","notification_deduplication","supplier_lead_times","store_settings","product_reviews","audit_entries","notifications","restock_requests","supplier_returns","consultation_notes","shipment_events","warranty_verifications","market_offers","recommendation_impressions","interaction_events","compatibility_reviews","rma_cases","warranties","unit_allocations","payments","shipments","cart_items","shopping_carts","order_items","inventory_units","inventory_balances","sales_orders","compatibility_rules","product_variants","products","customers"))s.executeUpdate("DELETE FROM "+table);
        }
        TechZoneStore.RelationalAuxiliaryState aux=store.relationalAuxiliaryState();
        Map<String,Boolean> consent=aux.consents();
        try(PreparedStatement p=c.prepareStatement("INSERT INTO customers(customer_id,email,full_name,phone_number,address,password_salt,password_hash,account_status,created_at,updated_at,analytics_consent) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            for(var a:store.relationalAccounts()){p.setString(1,a.id());p.setString(2,a.email());p.setString(3,a.name());p.setString(4,Objects.toString(a.phoneNumber(),"NOT_PROVIDED"));p.setString(5,a.address());p.setBytes(6,a.salt());p.setBytes(7,a.passwordHash());p.setString(8,"ACTIVE");p.setString(9,time(a.createdAt()));p.setString(10,time(a.updatedAt()));p.setInt(11,consent.getOrDefault(a.id(),false)?1:0);p.addBatch();}p.executeBatch();
        }
        Map<String,ProductVariant> variants=new LinkedHashMap<>();for(ProductVariant v:store.products())variants.put(v.id,v);
        try(PreparedStatement p=c.prepareStatement("INSERT INTO products(product_id,product_name,category,brand_name,description,image_url,created_at,is_active) VALUES(?,?,?,?,?,?,?,?)")) {
            for(var family:store.productFamilies()){List<ProductVariant> vs=family.variants();ProductVariant v=vs.get(0);p.setString(1,family.id);p.setString(2,family.name);p.setString(3,family.category);p.setString(4,family.brand);p.setString(5,family.description);p.setString(6,"/v1/products/"+family.id+"/image");p.setString(7,time(v.createdAt));p.setInt(8,family.active()?1:0);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO product_variants(variant_id,product_id,sku,variant_name,color,capacity,price,sale_price,warranty_months,technical_specs,is_active,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(ProductVariant v:variants.values()){p.setString(1,v.id);p.setString(2,v.productId());p.setString(3,v.sku);p.setString(4,v.name);p.setString(5,Objects.toString(v.color,""));p.setString(6,firstSpec(v));p.setLong(7,v.price);p.setLong(8,v.salePrice);p.setInt(9,v.warrantyMonths);p.setString(10,json(v.specs));p.setInt(11,v.variantActiveForStorage()?1:0);p.setString(12,time(v.updatedAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse_code,updated_at) VALUES(?,?,?,?,?,?,?)")) {
            for(ProductVariant v:variants.values()){InventoryBalance b=store.balance(v.id);p.setString(1,v.id);p.setInt(2,b.available);p.setInt(3,b.reserved);p.setInt(4,b.allocated);p.setInt(5,b.reorderThreshold);p.setString(6,Objects.toString(b.warehouse,"UNASSIGNED"));p.setString(7,time(LocalDateTime.now()));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO sales_orders(order_id,customer_id,order_number,customer_name,shipping_address,payment_method,order_status,total_amount,currency_code,tax_amount,shipping_fee,created_at,idempotency_key,subtotal,discount,promotion_code,payment_reference,tracking_code,carrier,package_weight_grams,package_dimensions,sales_verified_by,sales_verified_at,pending_reason,paid_at,delivered_at,reconciliation_due_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(SalesOrder o:store.orders()){p.setString(1,o.id);p.setString(2,o.customerId);p.setString(3,o.number);p.setString(4,o.customerName);p.setString(5,o.shippingAddress);p.setString(6,o.paymentMethod);p.setString(7,o.status.name());p.setLong(8,o.total);p.setString(9,"VND");p.setLong(10,o.tax);p.setLong(11,o.shippingFee);p.setString(12,time(o.createdAt));p.setString(13,o.idempotencyKey);p.setLong(14,o.subtotal);p.setLong(15,o.discount);p.setString(16,Objects.toString(o.promotionCode,"NONE"));p.setString(17,Objects.toString(o.paymentReference,""));p.setString(18,Objects.toString(o.trackingCode,""));p.setString(19,Objects.toString(o.carrier,""));p.setInt(20,o.packageWeightGrams);p.setString(21,Objects.toString(o.packageDimensions,""));p.setString(22,Objects.toString(o.salesVerifiedBy,"SYSTEM_IMPORT"));p.setString(23,time(o.salesVerifiedAt));p.setString(24,Objects.toString(o.pendingReason,"NONE"));p.setString(25,time(o.paidAt));p.setString(26,time(o.deliveredAt));p.setString(27,time(o.reconciliationDueAt));p.addBatch();}p.executeBatch();
        }
        Map<String,String> orderItemIds=new HashMap<>();
        try(PreparedStatement p=c.prepareStatement("INSERT INTO order_items(order_item_id,order_id,variant_id,quantity,unit_price,discount_amount,line_total,reserved_qty,allocated_qty,reservation_state,reservation_expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            for(SalesOrder o:store.orders())for(OrderLine line:o.lines){String id=UnitAllocation.orderItemKey(o.id,line.variantId);orderItemIds.put(o.id+"|"+line.variantId,id);p.setString(1,id);p.setString(2,o.id);p.setString(3,line.variantId);p.setInt(4,line.quantity);p.setLong(5,line.unitPrice);p.setLong(6,line.discountAmount);p.setLong(7,line.lineTotal());p.setInt(8,line.reservedQuantity);p.setInt(9,line.allocatedQuantity);p.setString(10,line.reservedQuantity>0?"RESERVED":line.allocatedQuantity>=line.quantity?"ALLOCATED":"OPEN");p.setString(11,time(o.reconciliationDueAt));p.addBatch();}p.executeBatch();
        }
        Map<String,List<CartItem>> carts=store.relationalCarts();Map<String,TechZoneStore.RelationalAccount> accounts=new HashMap<>();for(var a:store.relationalAccounts())accounts.put(a.id(),a);
        try(PreparedStatement cart=c.prepareStatement("INSERT INTO shopping_carts(cart_id,customer_id,session_key,cart_status,created_at,updated_at) VALUES(?,?,?,?,?,?)");PreparedStatement item=c.prepareStatement("INSERT INTO cart_items(cart_id,variant_id,quantity,unit_price_snapshot,added_at) VALUES(?,?,?,?,?)")) {
            for(var entry:carts.entrySet()){var account=accounts.get(entry.getKey());LocalDateTime created=account==null?LocalDateTime.now():account.createdAt();cart.setString(1,entry.getKey());cart.setString(2,entry.getKey());cart.setString(3,"CUSTOMER:"+entry.getKey());cart.setString(4,"OPEN");cart.setString(5,time(created));cart.setString(6,time(LocalDateTime.now()));cart.addBatch();for(CartItem i:entry.getValue()){item.setString(1,entry.getKey());item.setString(2,i.variantId);item.setInt(3,i.quantity);item.setLong(4,i.unitPriceSnapshot>0?i.unitPriceSnapshot:variants.get(i.variantId).effectivePrice());item.setString(5,time(i.addedAt));item.addBatch();}}cart.executeBatch();item.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO inventory_units(unit_id,variant_id,serial_imei,batch_number,received_at,warehouse_location,unit_status,order_id) VALUES(?,?,?,?,?,?,?,?)")) {
            for(ProductVariant v:variants.values())for(InventoryUnit u:store.unitsForVariant(v.id)){p.setString(1,u.serial);p.setString(2,u.variantId);p.setString(3,u.serial);p.setString(4,Objects.toString(u.batchNumber,"LEGACY-UNVERIFIED"));p.setString(5,time(u.receivedAt));p.setString(6,Objects.toString(u.warehouseLocation,"UNASSIGNED"));p.setString(7,u.status.name());p.setString(8,u.status==UnitStatus.AVAILABLE?null:u.orderId);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO payments(payment_id,order_id,payment_method,provider_ref,idempotency_key,amount,currency_code,payment_status,authorized_at,captured_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            for(SalesOrder o:store.orders()){String status=!Objects.toString(o.paymentReference,"").isBlank()?"AUTHORIZED":o.status==OrderStatus.CANCELLED?"DECLINED":"UNKNOWN";p.setString(1,o.id);p.setString(2,o.id);p.setString(3,o.paymentMethod);p.setString(4,Objects.toString(o.paymentReference,""));p.setString(5,o.idempotencyKey);p.setLong(6,o.total);p.setString(7,"VND");p.setString(8,status);p.setString(9,time(o.paidAt));p.setString(10,time(o.paidAt));p.setString(11,time(o.paidAt==null?o.createdAt:o.paidAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO shipments(shipment_id,order_id,tracking_code,carrier_name,package_weight_grams,package_dimensions,shipped_at,shipment_status,delivered_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for(SalesOrder o:store.orders())if(o.trackingCode!=null&&!o.trackingCode.isBlank()){p.setString(1,o.id);p.setString(2,o.id);p.setString(3,o.trackingCode);p.setString(4,Objects.toString(o.carrier,"UNKNOWN"));p.setInt(5,o.packageWeightGrams);p.setString(6,Objects.toString(o.packageDimensions,""));p.setString(7,time(o.shippedAt));p.setString(8,o.status.name());p.setString(9,time(o.deliveredAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO unit_allocations(allocation_id,order_item_id,unit_id,variant_id,allocation_status,allocated_by,scanned_at) VALUES(?,?,?,?,?,?,?)")) {
            for(SalesOrder o:store.orders())for(OrderLine line:o.lines)for(String serial:line.serials){UnitAllocation evidence=line.allocationDetails(serial);p.setString(1,serial);p.setString(2,orderItemIds.get(o.id+"|"+line.variantId));p.setString(3,serial);p.setString(4,line.variantId);p.setString(5,"ALLOCATED");p.setString(6,evidence==null?"SYSTEM_IMPORT":Objects.toString(evidence.allocatedBy(),"SYSTEM_IMPORT"));p.setString(7,time(evidence==null?o.createdAt:evidence.scannedAt()));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO warranties(warranty_id,allocation_id,activated_at,expires_at,coverage_type,warranty_status,unit_id,variant_id,order_id,customer_id) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for(Warranty w:store.warranties()){p.setString(1,w.serial);p.setString(2,w.serial);p.setString(3,time(w.activatedAt));p.setString(4,time(w.expiresAt));p.setString(5,"STANDARD");p.setString(6,w.status.name());p.setString(7,w.serial);p.setString(8,w.variantId);p.setString(9,w.orderId);p.setString(10,w.customerId);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO rma_cases(rma_id,warranty_id,customer_id,problem_description,reason_code,resolution_notes,closed_at,rma_status,created_at,assigned_to,diagnosis,replacement_parts,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(RmaCase r:store.rmas()){p.setString(1,r.id);p.setString(2,r.serial);p.setString(3,r.customerId);p.setString(4,r.problem);p.setString(5,r.type);p.setString(6,Objects.toString(r.resolutionNotes,""));p.setString(7,terminalRma(r.status)?time(r.updatedAt):time((LocalDateTime)null));p.setString(8,r.status);p.setString(9,time(r.createdAt));p.setString(10,Objects.toString(r.assignedTo,""));p.setString(11,Objects.toString(r.diagnosis,""));p.setString(12,Objects.toString(r.replacementParts,""));p.setString(13,time(r.updatedAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO compatibility_rules(rule_id,primary_variant_id,accessory_variant_id,is_approved,rule_type,rationale,verified_by,verified_at) VALUES(?,?,?,?,?,?,?,?)")) {
            for(var pair:store.relationalCompatibilityPairs()){String id=pair.left()+"|"+pair.right();p.setString(1,id);p.setString(2,pair.left());p.setString(3,pair.right());p.setInt(4,1);p.setString(5,"APPROVED");p.setString(6,"Approved compatibility matrix entry.");p.setString(7,"SYSTEM_IMPORT");p.setString(8,time((LocalDateTime)null));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO compatibility_reviews(review_id,sequence,primary_variant_id,accessory_variant_id,reason,created_at,review_status,reviewed_by,review_note,reviewed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            int i=0;for(CompatibilityReview r:store.compatibilityReviews()){p.setString(1,r.id);p.setInt(2,++i);p.setString(3,r.primaryVariantId);p.setString(4,r.accessoryVariantId);p.setString(5,r.reason);p.setString(6,time(r.createdAt));p.setString(7,r.status);p.setString(8,Objects.toString(r.reviewedBy,""));p.setString(9,Objects.toString(r.reviewNote,""));p.setString(10,time(r.reviewedAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO interaction_events(event_id,customer_id,anonymous_session_key,variant_id,request_id,event_type,event_source,consent_scope,search_query,filters,sort_by,page,event_context,detail,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i=0;for(InteractionEvent e:store.interactionEvents()){p.setString(1,Integer.toString(++i));p.setString(2,e.customerId);p.setString(3,Objects.toString(e.anonymousSessionKey,"LEGACY:"+i));p.setString(4,e.variantId);p.setString(5,Objects.toString(e.requestId,"LEGACY-"+i));p.setString(6,e.type);p.setString(7,Objects.toString(e.eventSource,"LEGACY_IMPORT"));p.setString(8,Objects.toString(e.consentScope,"UNKNOWN"));p.setString(9,Objects.toString(e.searchQuery,""));p.setString(10,Objects.toString(e.filters,"{}"));p.setString(11,Objects.toString(e.sortBy,"RELEVANCE"));p.setInt(12,e.page==null?1:e.page);p.setString(13,Objects.toString(e.eventContext,e.detail));p.setString(14,e.detail);p.setString(15,time(e.occurredAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO recommendation_impressions(impression_id,request_id,customer_id,primary_variant_id,accessory_variant_id,rank_position,score,strategy,model_version,generated_at,shown_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            for(var i:store.recommendationImpressions()){p.setString(1,i.id);p.setString(2,i.requestId);p.setString(3,i.customerId);p.setString(4,i.primaryId);p.setString(5,i.accessoryId);p.setInt(6,i.rank);if(i.score==null)p.setNull(7,Types.REAL);else p.setDouble(7,i.score);p.setString(8,i.strategy);p.setString(9,i.modelVersion);p.setString(10,time(i.shownAt));p.setString(11,time(i.shownAt));p.addBatch();}p.executeBatch();
        }
        writeAuxiliary(c,store,aux);
    }

    private static String firstSpec(ProductVariant v){return v.specs.getOrDefault("capacity",v.specs.getOrDefault("storage","NOT_SPECIFIED"));}
    private static boolean terminalRma(String status){return Set.of("Hoàn tất","Từ chối","COMPLETED","REJECTED").contains(status);}
    private static void writeMarketOffers(Connection c,List<MarketOffer> offers) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT OR REPLACE INTO market_offers(variant_id,source_sku,source_url,price,availability,fetched_at) VALUES(?,?,?,?,?,?)")) {
            for(var o:offers){p.setString(1,o.productId());p.setString(2,o.sourceSku());p.setString(3,o.url());p.setLong(4,o.price());p.setString(5,o.availability());p.setString(6,o.fetchedAt());p.addBatch();}p.executeBatch();
        }
    }

    private static void writeAuxiliary(Connection c,TechZoneStore store,TechZoneStore.RelationalAuxiliaryState a) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO warranty_verifications VALUES(?,?,?,?,?,?,?)")) {
            for(WarrantyVerification v:a.warrantyVerifications()){p.setString(1,v.id);p.setString(2,v.serial);p.setString(3,v.customerId);p.setString(4,v.problem);p.setString(5,time(v.createdAt));p.setString(6,v.status);p.setString(7,v.result);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO shipment_events VALUES(?,?,?,?,?,?)")) {
            for(ShipmentEvent e:a.shipmentEvents()){p.setString(1,e.id);p.setString(2,e.orderId);p.setString(3,e.milestone);p.setString(4,e.location);p.setString(5,e.detail);p.setString(6,time(e.occurredAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications VALUES(?,?,?,?,?,?)")) {
            for(AppNotification n:a.notifications()){p.setString(1,n.id);p.setString(2,n.audience);p.setString(3,n.title);p.setString(4,n.message);p.setString(5,time(n.createdAt));p.setInt(6,n.read?1:0);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO consultation_notes VALUES(?,?,?,?,?)")) {
            for(ConsultationNote n:a.consultationNotes()){p.setString(1,n.id);p.setString(2,n.orderId);p.setString(3,n.author);p.setString(4,n.note);p.setString(5,time(n.createdAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO audit_entries(actor,action,detail,occurred_at) VALUES(?,?,?,?)")) {
            for(AuditEntry e:a.auditEntries()){p.setString(1,e.actor);p.setString(2,e.action);p.setString(3,e.detail);p.setString(4,time(e.occurredAt));p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO restock_requests VALUES(?,?,?,?,?,?,?,?)")) {
            for(RestockRequest r:a.restockRequests()){p.setString(1,r.id);p.setString(2,r.variantId);p.setInt(3,r.quantity);p.setString(4,r.supplier);p.setString(5,time(r.createdAt));p.setString(6,r.status);p.setInt(7,r.leadDays);p.setString(8,r.supplierReference);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO supplier_returns VALUES(?,?,?,?,?,?,?,?)")) {
            for(SupplierReturn r:a.supplierReturns()){p.setString(1,r.id);p.setString(2,r.variantId);p.setString(3,r.serial);p.setString(4,r.reason);p.setString(5,r.supplier);p.setString(6,r.createdBy);p.setString(7,time(r.createdAt));p.setString(8,r.status);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO product_reviews(reviewer_name,product_name,review_text,rating,status) VALUES(?,?,?,?,?)")) {
            for(String[] r:a.reviews()){p.setString(1,r[0]);p.setString(2,r[1]);p.setString(3,r[2]);p.setInt(4,Integer.parseInt(r[3]));p.setString(5,r[4]);p.addBatch();}p.executeBatch();
        }
        for(String id:a.paymentCallbackIds())putCallback(c,"PAYMENT",id,null);
        for(String id:a.deliveryEvents())putCallback(c,"DELIVERY",id,null);
        for(String id:a.shipmentCallbackIds())putCallback(c,"SHIPMENT",id,null);
        for(String id:a.escalatedPaymentOrders())putCallback(c,"ESCALATED",id,id);
        try(PreparedStatement p=c.prepareStatement("INSERT INTO recommendation_attributions(owner_id,order_id,variant_id) VALUES(?,?,?)")) {
            for(var e:a.recommendedCartItems().entrySet())for(String id:e.getValue()){p.setString(1,e.getKey());p.setString(2,"");p.setString(3,id);p.addBatch();}
            for(var e:a.recommendedOrderItems().entrySet())for(String id:e.getValue()){p.setString(1,store.order(e.getKey())==null?"":store.order(e.getKey()).customerId);p.setString(2,e.getKey());p.setString(3,id);p.addBatch();}p.executeBatch();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO supplier_lead_times VALUES(?,?)")) {for(var e:a.supplierLeadDays().entrySet()){p.setString(1,e.getKey());p.setInt(2,e.getValue());p.addBatch();}p.executeBatch();}
        try(PreparedStatement p=c.prepareStatement("INSERT INTO notification_deduplication VALUES(?)")){for(String id:a.lowStockNotified()){p.setString(1,id.replaceFirst("^LOW-STOCK-",""));p.addBatch();}p.executeBatch();}
        Map<String,String> settings=new LinkedHashMap<>();settings.put("recommendation_limit",Integer.toString(a.recommendationLimit()));settings.put("monthly_sales_target",Long.toString(a.monthlySalesTarget()));settings.put("promotion_percent",Integer.toString(a.promotionPercent()));settings.put("catalog_revision",Integer.toString(a.catalogRevision()));settings.put("search_index_revision",Integer.toString(a.searchIndexRevision()));settings.put("recommendation_index_revision",Integer.toString(a.recommendationIndexRevision()));settings.put("order_sequence",Long.toString(a.orderSequence()));settings.put("rma_sequence",Integer.toString(a.rmaSequence()));settings.put("verification_sequence",Integer.toString(a.verificationSequence()));settings.put("notification_sequence",Integer.toString(a.notificationSequence()));settings.put("note_sequence",Integer.toString(a.noteSequence()));settings.put("customer_sequence",Integer.toString(a.customerSequence()));
        try(PreparedStatement p=c.prepareStatement("INSERT INTO store_settings VALUES(?,?)")){for(var e:settings.entrySet()){p.setString(1,e.getKey());p.setString(2,e.getValue());p.addBatch();}for(var e:a.lastSearchFilters().entrySet()){p.setString(1,"last_search:"+e.getKey());p.setString(2,e.getValue());p.addBatch();}p.executeBatch();}
    }
    private static void putCallback(Connection c,String provider,String id,String orderId) throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO provider_callbacks(provider,callback_id,order_id) VALUES(?,?,?)")){p.setString(1,provider);p.setString(2,id);p.setString(3,orderId);p.executeUpdate();}
    }

    private void loadRows(TechZoneStore store,String activeCustomerId) throws Exception {
        Map<String,ProductVariant> variants=new LinkedHashMap<>();Map<String,String> familyActive=new HashMap<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT v.variant_id,v.product_id,p.product_name,v.sku,v.variant_name,p.category,p.brand_name,p.description,v.color,v.price,v.sale_price,v.warranty_months,v.technical_specs,p.is_active,v.is_active,p.created_at,v.updated_at FROM product_variants v JOIN products p ON p.product_id=v.product_id ORDER BY v.variant_id")) {
            while(r.next()){ProductVariant v=new ProductVariant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getLong(10),r.getLong(11),r.getInt(12),parseJson(r.getString(13)),r.getInt(14)!=0,r.getInt(15)!=0);v.createdAt=time(r.getString(16));v.updatedAt=time(r.getString(17));variants.put(v.id,v);}
        }
        List<InventoryBalance> balances=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse_code FROM inventory_balances")){while(r.next())balances.add(new InventoryBalance(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getString(6)));}
        Map<String,Payment> payments=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT payment_id,order_id,payment_method,provider_ref,idempotency_key,amount,currency_code,payment_status,authorized_at,captured_at,updated_at FROM payments")){while(r.next())payments.put(r.getString(2),new Payment(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getString(7),r.getString(8),time(r.getString(9)),time(r.getString(10)),time(r.getString(11))));}
        Map<String,Shipment> shipments=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT shipment_id,order_id,tracking_code,carrier_name,package_weight_grams,package_dimensions,shipped_at,shipment_status,delivered_at FROM shipments")){while(r.next())shipments.put(r.getString(2),new Shipment(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6),time(r.getString(7)),r.getString(8),time(r.getString(9))));}
        Map<String,SalesOrder> orders=new LinkedHashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT order_id,order_number,customer_id,customer_name,shipping_address,payment_method,idempotency_key,promotion_code,created_at,order_status,total_amount,subtotal,discount,tax_amount,shipping_fee,payment_reference,tracking_code,carrier,package_weight_grams,package_dimensions,sales_verified_by,sales_verified_at,pending_reason,paid_at,delivered_at,reconciliation_due_at FROM sales_orders ORDER BY created_at")) {
            while(r.next()){Payment payment=payments.get(r.getString(1));if(payment==null)throw new IOException("Thiếu payment cho đơn hàng "+r.getString(1));SalesOrder o=new SalesOrder(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),payment.method(),r.getString(7),r.getString(8),time(r.getString(9)));o.status=OrderStatus.valueOf(r.getString(10));o.total=r.getLong(11);o.subtotal=r.getLong(12);o.discount=r.getLong(13);o.tax=r.getLong(14);o.shippingFee=r.getLong(15);o.paymentReference=payment.providerReference();o.trackingCode=r.getString(17);o.carrier=r.getString(18);o.packageWeightGrams=r.getInt(19);o.packageDimensions=r.getString(20);o.salesVerifiedBy=r.getString(21);o.salesVerifiedAt=time(r.getString(22));o.pendingReason=r.getString(23);o.paidAt=time(r.getString(24));o.deliveredAt=time(r.getString(25));o.reconciliationDueAt=time(r.getString(26));Shipment shipment=shipments.get(o.id);if(shipment!=null){o.trackingCode=shipment.trackingCode();o.carrier=shipment.carrier();o.packageWeightGrams=Math.toIntExact(shipment.weightGrams());o.packageDimensions=shipment.dimensions();o.shippedAt=shipment.shippedAt();}orders.put(o.id,o);}
        }
        Map<String,OrderLine> orderLines=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT order_id,variant_id,quantity,unit_price,reserved_qty,allocated_qty,discount_amount FROM order_items ORDER BY order_id,variant_id")){while(r.next()){SalesOrder o=orders.get(r.getString(1));if(o==null)throw new IOException("Order item không có order.");OrderLine line=new OrderLine(r.getString(2),r.getInt(3),r.getLong(4));line.reservedQuantity=r.getInt(5);line.allocatedQuantity=r.getInt(6);line.discountAmount=r.getLong(7);o.lines.add(line);orderLines.put(r.getString(1)+"|"+r.getString(2),line);}}
        List<InventoryUnit> units=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT unit_id,variant_id,serial_imei,batch_number,received_at,warehouse_location,unit_status,order_id FROM inventory_units ORDER BY unit_id")){while(r.next()){InventoryUnit u=new InventoryUnit(r.getString(3),r.getString(2),time(r.getString(5)),r.getString(4),r.getString(6));u.status=UnitStatus.valueOf(r.getString(7));u.orderId=r.getString(8);units.add(u);}}
        Map<String,InventoryUnit> unitsById=new HashMap<>();for(InventoryUnit unit:units)unitsById.put(unit.serial,unit);
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT a.allocation_id,a.order_item_id,a.unit_id,a.variant_id,a.allocation_status,a.allocated_by,a.scanned_at,i.order_id,u.serial_imei FROM unit_allocations a JOIN order_items i ON i.order_item_id=a.order_item_id JOIN inventory_units u ON u.unit_id=a.unit_id")) {
            while(r.next()){OrderLine line=orderLines.get(r.getString(8)+"|"+r.getString(4));InventoryUnit unit=unitsById.get(r.getString(3));if(line==null||unit==null)throw new IOException("Allocation không khớp order item/unit.");line.serials.add(unit.serial);LocalDateTime scanned=time(r.getString(7));if(scanned==null)scanned=LocalDateTime.now();line.recordAllocation(new UnitAllocation(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),scanned));}
        }
        store.restoreRelationalCore(new ArrayList<>(variants.values()),balances,units,new ArrayList<>(orders.values()));
        List<TechZoneStore.RelationalAccount> accounts=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT customer_id,full_name,email,address,password_salt,password_hash,phone_number,created_at,updated_at FROM customers ORDER BY customer_id")){while(r.next())accounts.add(new TechZoneStore.RelationalAccount(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getBytes(5),r.getBytes(6),r.getString(7),time(r.getString(8)),time(r.getString(9))));}
        Map<String,List<CartItem>> carts=new LinkedHashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT cart_id,customer_id FROM shopping_carts")){while(r.next())carts.put(r.getString(2),new ArrayList<>());}
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT c.customer_id,i.variant_id,i.quantity,i.unit_price_snapshot,i.added_at FROM cart_items i JOIN shopping_carts c ON c.cart_id=i.cart_id")){while(r.next()){var cart=carts.get(r.getString(1));if(cart==null)throw new IOException("Cart item không có cart.");LocalDateTime added=time(r.getString(5));cart.add(new CartItem(r.getString(2),r.getInt(3),r.getLong(4),added,added));}}
        store.restoreRelationalAccounts(accounts,carts,activeCustomerId);
        List<TechZoneStore.RelationalPair> pairs=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT primary_variant_id,accessory_variant_id FROM compatibility_rules WHERE is_approved=1")){while(r.next())pairs.add(new TechZoneStore.RelationalPair(r.getString(1),r.getString(2)));}
        List<InteractionEvent> events=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT event_type,detail,occurred_at,customer_id,variant_id,anonymous_session_key,request_id,event_source,consent_scope,search_query,filters,sort_by,page,event_context FROM interaction_events ORDER BY rowid")){while(r.next()){Integer page=(Integer)r.getObject(13);events.add(new InteractionEvent(r.getString(1),r.getString(2),time(r.getString(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),page,r.getString(14)));}}
        store.restoreRelationalDiscovery(pairs,events);
        List<RecommendationImpression> impressions=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT impression_id,request_id,customer_id,primary_variant_id,accessory_variant_id,rank_position,score,strategy,model_version,shown_at FROM recommendation_impressions ORDER BY rowid")){while(r.next()){Double score=r.getObject(7)==null?null:r.getDouble(7);impressions.add(new RecommendationImpression(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),score,r.getString(8),r.getString(9),time(r.getString(10))));}}
        store.restoreRelationalImpressions(impressions);
        List<CompatibilityReview> reviews=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT review_id,primary_variant_id,accessory_variant_id,reason,created_at,review_status,reviewed_by,review_note,reviewed_at FROM compatibility_reviews ORDER BY sequence")){while(r.next()){CompatibilityReview review=new CompatibilityReview(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)));review.status=r.getString(6);review.reviewedBy=r.getString(7);review.reviewNote=r.getString(8);review.reviewedAt=time(r.getString(9));reviews.add(review);}}
        store.restoreRelationalCompatibilityReviews(reviews);
        List<Warranty> warranties=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT unit_id,variant_id,order_id,customer_id,activated_at,expires_at,warranty_status FROM warranties")){while(r.next())warranties.add(new Warranty(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)),time(r.getString(6)),WarrantyStatus.valueOf(r.getString(7))));}
        List<RmaCase> rmas=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT r.rma_id,w.unit_id,r.customer_id,r.problem_description,r.created_at,r.reason_code,r.rma_status,r.assigned_to,r.diagnosis,r.resolution_notes,r.replacement_parts,r.updated_at FROM rma_cases r JOIN warranties w USING(warranty_id)")){while(r.next()){RmaCase item=new RmaCase(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)),r.getString(6));item.status=r.getString(7);item.assignedTo=r.getString(8);item.diagnosis=r.getString(9);item.resolutionNotes=r.getString(10);item.replacementParts=r.getString(11);item.updatedAt=time(r.getString(12));rmas.add(item);}}
        store.restoreRelationalSupport(warranties,rmas);store.validateInventoryIntegrity();store.validateSupportIntegrity();
        loadAuxiliary(store);
    }

    private void loadAuxiliary(TechZoneStore store) throws Exception {
        List<WarrantyVerification> verifications=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT verification_id,unit_id,customer_id,problem,created_at,verification_status,result FROM warranty_verifications")){while(r.next()){WarrantyVerification v=new WarrantyVerification(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)));v.status=r.getString(6);v.result=r.getString(7);verifications.add(v);}}
        List<ShipmentEvent> shipments=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT event_id,order_id,milestone,location,detail,occurred_at FROM shipment_events")){while(r.next())shipments.add(new ShipmentEvent(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),time(r.getString(6))));}
        List<AppNotification> notifications=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT notification_id,audience,title,message,created_at,is_read FROM notifications")){while(r.next()){AppNotification n=new AppNotification(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5)));n.read=r.getInt(6)!=0;notifications.add(n);}}
        List<ConsultationNote> notes=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT note_id,order_id,author,note,created_at FROM consultation_notes")){while(r.next())notes.add(new ConsultationNote(r.getString(1),r.getString(2),r.getString(3),r.getString(4),time(r.getString(5))));}
        List<AuditEntry> audits=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT actor,action,detail,occurred_at FROM audit_entries ORDER BY audit_id DESC")){while(r.next())audits.add(new AuditEntry(r.getString(1),r.getString(2),r.getString(3),time(r.getString(4))));}
        List<RestockRequest> restocks=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT restock_id,variant_id,quantity,supplier,created_at,request_status,lead_days,supplier_reference FROM restock_requests")){while(r.next()){RestockRequest x=new RestockRequest(r.getString(1),r.getString(2),r.getInt(3),r.getString(4),time(r.getString(5)));x.status=r.getString(6);x.leadDays=r.getInt(7);x.supplierReference=r.getString(8);restocks.add(x);}}
        List<SupplierReturn> returns=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT return_id,variant_id,unit_id,reason,supplier,created_by,created_at,return_status FROM supplier_returns")){while(r.next()){SupplierReturn x=new SupplierReturn(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),time(r.getString(7)));x.status=r.getString(8);returns.add(x);}}
        List<String[]> reviews=new ArrayList<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT reviewer_name,product_name,review_text,rating,status FROM product_reviews ORDER BY review_id")){while(r.next())reviews.add(new String[]{r.getString(1),r.getString(2),r.getString(3),Integer.toString(r.getInt(4)),r.getString(5)});}
        Map<String,Boolean> consents=new LinkedHashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT customer_id,analytics_consent FROM customers")){while(r.next())consents.put(r.getString(1),r.getInt(2)!=0);}
        Map<String,Integer> leadDays=new LinkedHashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT variant_id,lead_days FROM supplier_lead_times")){while(r.next())leadDays.put(r.getString(1),r.getInt(2));}
        Set<String> payments=new LinkedHashSet<>(),escalated=new LinkedHashSet<>(),deliveries=new LinkedHashSet<>(),shipCallbacks=new LinkedHashSet<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT provider,callback_id,order_id FROM provider_callbacks")){while(r.next())switch(r.getString(1)){case "PAYMENT"->payments.add(r.getString(2));case "ESCALATED"->escalated.add(r.getString(2));case "DELIVERY"->deliveries.add(r.getString(2));case "SHIPMENT"->shipCallbacks.add(r.getString(2));default->{}}}
        Set<String> lowStock=new LinkedHashSet<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT variant_id FROM notification_deduplication")){while(r.next())lowStock.add("LOW-STOCK-"+r.getString(1));}
        Map<String,Set<String>> cartRec=new LinkedHashMap<>(),orderRec=new LinkedHashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT owner_id,order_id,variant_id FROM recommendation_attributions")){while(r.next()){String order=r.getString(2);(order==null||order.isEmpty()?cartRec:orderRec).computeIfAbsent(order==null||order.isEmpty()?r.getString(1):order,k->new LinkedHashSet<>()).add(r.getString(3));}}
        Map<String,String> filters=new LinkedHashMap<>();Map<String,String> settings=new HashMap<>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT setting_key,setting_value FROM store_settings")){while(r.next()){String k=r.getString(1),v=r.getString(2);if(k.startsWith("last_search:"))filters.put(k.substring(12),v);else settings.put(k,v);}}
        Map<String,Integer> intSettings=new HashMap<>();for(String k:List.of("recommendation_limit","promotion_percent","rma_sequence","verification_sequence","notification_sequence","note_sequence","customer_sequence"))intSettings.put(k,Integer.parseInt(settings.getOrDefault(k,"0")));
        TechZoneStore.RelationalAuxiliaryState aux=new TechZoneStore.RelationalAuxiliaryState(verifications,shipments,notifications,notes,audits,restocks,returns,reviews,consents,leadDays,payments,escalated,deliveries,shipCallbacks,lowStock,cartRec,orderRec,filters,intSettings.get("recommendation_limit"),Long.parseLong(settings.getOrDefault("monthly_sales_target","0")),intSettings.get("promotion_percent"),Integer.parseInt(settings.getOrDefault("catalog_revision","1")),Integer.parseInt(settings.getOrDefault("search_index_revision","1")),Integer.parseInt(settings.getOrDefault("recommendation_index_revision","1")),Long.parseLong(settings.getOrDefault("order_sequence","1043")),intSettings.get("rma_sequence"),intSettings.get("verification_sequence"),intSettings.get("notification_sequence"),intSettings.get("note_sequence"),intSettings.get("customer_sequence"));
        store.restoreRelationalAuxiliaryState(aux);
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
