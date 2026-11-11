package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;
import vn.techzone.backend.CatalogBackend;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Comparator;

/** Verifies the actual product-family/variant relationship and persisted references. */
public final class DesignSchemaRegression {
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"design-schema-").toAbsolutePath().normalize();
        Path path=root.resolve("demo.sqlite");TechZoneStore seed=new TechZoneStore();
        try {
            try(SqliteStore db=new SqliteStore(path,seed)) {
                var eventState=db.read();TechZoneStore eventStore=SqliteStore.decodeSession(eventState.bytes());
                String allocatedSerial=eventStore.sampleReservedSerial("RAM-D5-32","ORD-1042");
                try {
                    check(!allocatedSerial.isBlank(),"reserved serial fixture missing");
                    check(eventStore.allocateSerial("ORD-1042","RAM-D5-32",allocatedSerial,"Warehouse tester").startsWith("Đã"),"serial allocation failed");
                    eventStore.setInteractionConsent(true);eventStore.recordProductView("GPU-4060");
                    eventStore.flagCompatibility("CPU-14600K","MB-B760-D4","Check source",vn.techzone.domain.TechZoneModels.Role.SUPPORT,"tester");
                    var review=eventStore.compatibilityReviews().stream().filter(r->r.primaryVariantId.equals("CPU-14600K") && r.accessoryVariantId.equals("MB-B760-D4") && r.status.equals("Chờ rà soát")).findFirst().orElseThrow();
                    check(eventStore.reviewCompatibility(review.id,true,vn.techzone.domain.TechZoneModels.Role.SUPPORT,"tester","Reviewed source").startsWith("Đã"),"review approval failed");
                    db.save(eventStore,eventState.revision());
                }
                finally { eventStore.closeRepository(); }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
                    s.execute("PRAGMA foreign_keys=ON");
                    try(PreparedStatement q=c.prepareStatement("SELECT allocated_by,scanned_at FROM unit_allocations WHERE unit_id=?")) {
                        q.setString(1,allocatedSerial);
                        try(ResultSet r=q.executeQuery()) { check(r.next() && "Warehouse tester".equals(r.getString(1)) && r.getString(2)!=null,"allocation evidence must persist"); }
                    }
                    try(ResultSet r=s.executeQuery("SELECT customer_id,variant_id FROM interaction_events WHERE event_type='product_view' ORDER BY rowid DESC LIMIT 1")) {
                        check(r.next() && TechZoneStore.CUSTOMER_ID.equals(r.getString(1)) && "GPU-4060".equals(r.getString(2)),"event must persist customer and variant references");
                    }
                    try(ResultSet r=s.executeQuery("SELECT reviewed_by,review_note,reviewed_at FROM compatibility_reviews WHERE primary_variant_id='CPU-14600K' AND accessory_variant_id='MB-B760-D4'")) {
                        check(r.next() && "tester".equals(r.getString(1)) && "Reviewed source".equals(r.getString(2)) && r.getString(3)!=null,"review evidence and time must be relational");
                    }
                    s.executeUpdate("UPDATE compatibility_reviews SET review_note='Updated from SQL' WHERE primary_variant_id='CPU-14600K' AND accessory_variant_id='MB-B760-D4'");
                    s.executeUpdate("UPDATE payments SET captured_at='2026-01-02T10:00:00',updated_at='2026-01-02T10:01:00' WHERE order_id='ORD-1038'");
                    s.executeUpdate("UPDATE shipments SET shipped_at='2026-01-03T10:00:00' WHERE order_id='ORD-1038'");
                    for(String table:new String[]{"products","product_variants","inventory_balances","order_items"})
                        try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='"+table+"'")) { check(r.next() && r.getInt(1)==1,"missing design table "+table); }
                    for(String legacy:new String[]{"inventory","order_lines","product_specs","app_state"})
                        try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='"+legacy+"'")) { check(r.next() && r.getInt(1)==0,"legacy object must be absent: "+legacy); }
                    boolean hasVariantFk=false;
                    try(ResultSet r=s.executeQuery("PRAGMA foreign_key_list(inventory_balances)")) { while(r.next()) if(r.getString("table").equals("product_variants"))hasVariantFk=true; }
                    check(hasVariantFk,"stock balance must reference the variant");
                    for(String table:new String[]{"inventory_units","order_items","cart_items","compatibility_rules","recommendation_impressions","warranties","compatibility_reviews"}) {
                        boolean linked=false;
                        try(ResultSet r=s.executeQuery("PRAGMA foreign_key_list("+table+")")) { while(r.next()) if(r.getString("table").equals("product_variants"))linked=true; }
                        check(linked,table+" must reference product_variants");
                    }
                    boolean allocationLinked=false;
                    try(ResultSet r=s.executeQuery("PRAGMA foreign_key_list(unit_allocations)")) { while(r.next())if(r.getString("table").equals("order_items") && r.getString("to").equals("order_item_id"))allocationLinked=true; }
                    check(allocationLinked,"allocation must reference the renamed order-item variant");
                    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM unit_allocations a JOIN order_items i ON i.order_item_id=a.order_item_id JOIN inventory_units u ON u.unit_id=a.unit_id WHERE a.variant_id=i.variant_id AND u.variant_id=a.variant_id")) {
                        check(r.next() && r.getInt(1)>0,"allocated units must link to their design order item and variant");
                    }
                    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM payments p JOIN sales_orders o ON o.order_id=p.order_id WHERE p.payment_id=o.order_id AND p.amount=o.total_amount AND p.currency_code='VND'")) {
                        check(r.next() && r.getInt(1)>0,"payment design fields must reflect the order");
                    }
                    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM shipments s JOIN sales_orders o ON o.order_id=s.order_id WHERE s.shipment_id=o.order_id AND s.shipment_status=o.order_status")) {
                        check(r.next() && r.getInt(1)>0,"shipment design fields must reflect the order");
                    }
                    boolean rejected=false;
                    try { s.executeUpdate("INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse_code,updated_at) VALUES('UNKNOWN-VARIANT',0,0,0,1,'test','2026-01-01T00:00:00')"); }
                    catch(SQLException expected) { rejected=true; }
                    check(rejected,"unknown variant must fail the foreign key");
                    rejected=false;
                    try { s.executeUpdate("UPDATE sales_orders SET order_number=(SELECT order_number FROM sales_orders WHERE order_id='ORD-1038') WHERE order_id='ORD-1042' "); }
                    catch(SQLException expected) { rejected=true; }
                    check(rejected,"duplicate order number must be rejected");
                    rejected=false;
                    try { s.executeUpdate("UPDATE sales_orders SET idempotency_key=(SELECT idempotency_key FROM sales_orders WHERE order_id='ORD-1038') WHERE order_id='ORD-1042' "); }
                    catch(SQLException expected) { rejected=true; }
                    check(rejected,"duplicate nonempty idempotency key must be rejected");
                    s.executeUpdate("INSERT INTO product_variants(variant_id,product_id,sku,variant_name,color,capacity,price,sale_price,warranty_months,technical_specs,is_active,updated_at) SELECT 'GPU-4060-ALT',product_id,'TZ-TEST-ALT',variant_name || ' Alt',color,capacity,price+1000,0,warranty_months,technical_specs,is_active,updated_at FROM product_variants WHERE variant_id='GPU-4060'");
                    s.executeUpdate("INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse_code,updated_at) VALUES('GPU-4060-ALT',0,0,0,1,'test','2026-01-01T00:00:00')");
                    s.executeUpdate("UPDATE inventory_balances SET reorder_threshold=19 WHERE variant_id='GPU-4060'");
                    s.executeUpdate("UPDATE products SET is_active=0 WHERE product_id='GPU-4060'");
                }
                SqliteStore.State state=db.read();TechZoneStore loaded=SqliteStore.decodeSession(state.bytes());
                try {
                    check(loaded.product("GPU-4060-ALT")!=null && loaded.product("GPU-4060-ALT").productId().equals("GPU-4060"),"second variant must belong to the same product");
                    check(loaded.compatibilityReviews().stream().anyMatch(r->"Updated from SQL".equals(r.reviewNote)),"review data must be loaded from relational rows");
                    check(!loaded.product("GPU-4060").active && !loaded.product("GPU-4060-ALT").active,"inactive product hides its variants");
                    check(loaded.balance("GPU-4060").reorderThreshold==19,"direct writes reach canonical balance");
                    db.save(loaded,state.revision());
                } finally { loaded.closeRepository(); }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
                    try(ResultSet r=s.executeQuery("SELECT p.captured_at,p.updated_at,s.shipped_at FROM payments p JOIN shipments s ON s.order_id=p.order_id WHERE p.order_id='ORD-1038'")) {
                        check(r.next() && java.time.LocalDateTime.parse(r.getString(1)).equals(java.time.LocalDateTime.parse("2026-01-02T10:00:00"))
                                && java.time.LocalDateTime.parse(r.getString(2)).equals(java.time.LocalDateTime.parse("2026-01-02T10:01:00"))
                                && java.time.LocalDateTime.parse(r.getString(3)).equals(java.time.LocalDateTime.parse("2026-01-03T10:00:00")),"fulfillment evidence must survive save");
                    }
                    try(PreparedStatement q=c.prepareStatement("SELECT allocated_by,scanned_at FROM unit_allocations WHERE unit_id=?")) {
                        q.setString(1,allocatedSerial);
                        try(ResultSet r=q.executeQuery()) { check(r.next() && "Warehouse tester".equals(r.getString(1)) && r.getString(2)!=null,"allocation evidence must survive reload and save"); }
                    }
                    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM products WHERE product_id='GPU-4060'")) { check(r.next() && r.getInt(1)==1,"product family duplicated"); }
                    try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM product_variants WHERE product_id='GPU-4060'")) { check(r.next() && r.getInt(1)==2,"both variants must survive save"); }
                    try(ResultSet r=s.executeQuery("SELECT p.is_active,v.is_active FROM products p JOIN product_variants v ON v.product_id=p.product_id WHERE v.variant_id='GPU-4060-ALT'")) { check(r.next() && r.getInt(1)==0 && r.getInt(2)==1,"product and variant activation flags must remain independent"); }
                    try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { check(!r.next(),"foreign keys invalid after save"); }
                }
                TechZoneStore apiStore=SqliteStore.decodeSession(db.read().bytes());
                try(CatalogBackend api=new CatalogBackend(apiStore,root.resolve("images"),false)) {
                    HttpClient client=HttpClient.newHttpClient();
                    var request=HttpRequest.newBuilder(api.baseUri().resolve("/v1/product-families")).header("X-TechZone-Token",api.token()).GET().build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                    check(response.statusCode()==200 && response.body().contains("\"id\":\"GPU-4060-ALT\",\"productId\":\"GPU-4060\""),"family API must expose the product/variant relationship");
                } finally { apiStore.closeRepository(); }
            }
            try(SqliteStore reopened=new SqliteStore(path,new TechZoneStore())) {
                TechZoneStore loaded=SqliteStore.decode(reopened.read().bytes());
                try { check(loaded.product("GPU-4060-ALT")!=null && loaded.product("GPU-4060-ALT").productId().equals("GPU-4060"),"variant relation lost after restart"); }
                finally { loaded.closeRepository(); }
            }
            System.out.println("Design schema: product families, variant FKs, normalized schema and restart passed");
        } finally {
            seed.closeRepository();Path allowed=Path.of("target").toAbsolutePath().normalize();
            if(!root.startsWith(allowed))throw new IllegalStateException("Unexpected test directory");
            try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p); }
        }
    }
}
