package vn.techzone.backend;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Adds the report's logical field names as constrained SQLite columns while retaining legacy names. */
final class DesignFieldMigration {
    private DesignFieldMigration() { }
    private static final String UNRECORDED_AT="1900-01-01T00:00:01";
    private record Field(String name,String type,String reference) { }
    private static Field f(String name,String type) { return new Field(name,type,null); }
    private static Field fk(String name,String ref) { return new Field(name,"TEXT"," REFERENCES "+ref); }

    private static final Map<String,List<Field>> FIELDS = new LinkedHashMap<>();
    private static final Map<String,String> BACKFILL = new LinkedHashMap<>();
    static {
        fields("customers",f("customer_id","TEXT"),f("full_name","TEXT"),f("phone_number","TEXT"),f("account_status","TEXT"),f("created_at","TEXT"),f("updated_at","TEXT"));
        fields("products",f("product_id","TEXT"),f("product_name","TEXT"),f("brand_name","TEXT"),f("image_url","TEXT"),f("created_at","TEXT"),f("is_active","INTEGER"));
        fields("product_variants",f("variant_id","TEXT"),f("capacity","TEXT"),f("technical_specs","TEXT"),f("is_active","INTEGER"),f("updated_at","TEXT"));
        fields("compatibility_rules",f("rule_id","TEXT"),fk("primary_variant_id","product_variants(variant_id)"),fk("accessory_variant_id","product_variants(variant_id)"),f("is_approved","INTEGER"),f("rule_type","TEXT"),f("rationale","TEXT"),f("verified_by","TEXT"),f("verified_at","TEXT"));
        fields("inventory_balances",f("warehouse_code","TEXT"),f("updated_at","TEXT"));
        fields("inventory_units",f("unit_id","TEXT"),fk("variant_id","product_variants(variant_id)"),f("serial_imei","TEXT"),f("batch_number","TEXT"),f("received_at","TEXT"),f("warehouse_location","TEXT"),f("unit_status","TEXT"));
        fields("shopping_carts",f("cart_id","TEXT"),f("session_key","TEXT"),f("cart_status","TEXT"),f("created_at","TEXT"),f("updated_at","TEXT"));
        fields("cart_items",fk("cart_id","shopping_carts(cart_id)"),fk("variant_id","product_variants(variant_id)"),f("unit_price_snapshot","INTEGER"),f("added_at","TEXT"));
        fields("sales_orders",f("order_id","TEXT"),f("order_number","TEXT"),f("order_status","TEXT"),f("total_amount","INTEGER"),f("currency_code","TEXT"),f("tax_amount","INTEGER"));
        fields("order_items",f("discount_amount","INTEGER"),f("line_total","INTEGER"),f("reservation_state","TEXT"),f("reservation_expires_at","TEXT"));
        fields("warranties",f("warranty_id","TEXT"),fk("allocation_id","unit_allocations(allocation_id)"),f("coverage_type","TEXT"),f("warranty_status","TEXT"));
        fields("rma_cases",f("rma_id","TEXT"),fk("warranty_id","warranties(warranty_id)"),f("problem_description","TEXT"),f("reason_code","TEXT"),f("closed_at","TEXT"),f("rma_status","TEXT"));
        fields("interaction_events",f("event_id","TEXT"),f("anonymous_session_key","TEXT"),f("request_id","TEXT"),f("event_source","TEXT"),f("consent_scope","TEXT"),f("search_query","TEXT"),f("filters","TEXT"),f("sort_by","TEXT"),f("page","INTEGER"),f("event_context","TEXT"));
        fields("recommendation_impressions",f("impression_id","TEXT"),fk("primary_variant_id","product_variants(variant_id)"),fk("accessory_variant_id","product_variants(variant_id)"),f("score","REAL"),f("generated_at","TEXT"));

        BACKFILL.put("customers","UPDATE customers SET customer_id=id,full_name=name,account_status=COALESCE(NULLIF(account_status,''),'ACTIVE')");
        BACKFILL.put("products","UPDATE products SET product_id=id,product_name=name,brand_name=brand,image_url=COALESCE(NULLIF(image_url,''),'/v1/products/'||id||'/image'),is_active=active");
        BACKFILL.put("product_variants","UPDATE product_variants SET variant_id=id,capacity=COALESCE((SELECT spec_value FROM product_specs WHERE product_id=product_variants.id AND lower(spec_key) IN ('capacity','storage') ORDER BY CASE lower(spec_key) WHEN 'capacity' THEN 0 ELSE 1 END LIMIT 1),''),is_active=active");
        BACKFILL.put("compatibility_rules","UPDATE compatibility_rules SET rule_id=left_variant_id||'|'||right_variant_id,primary_variant_id=left_variant_id,accessory_variant_id=right_variant_id,is_approved=COALESCE(is_approved,1),rule_type=COALESCE(NULLIF(rule_type,''),'APPROVED'),rationale=COALESCE(NULLIF(rationale,''),(SELECT review_note FROM compatibility_reviews c WHERE ((c.primary_variant_id=compatibility_rules.left_variant_id AND c.accessory_variant_id=compatibility_rules.right_variant_id) OR (c.primary_variant_id=compatibility_rules.right_variant_id AND c.accessory_variant_id=compatibility_rules.left_variant_id)) AND c.status='Đã xác minh · cho phép' ORDER BY c.seq DESC LIMIT 1),'Quy tắc thuộc ma trận tương thích hiện có; điều kiện vật lý và BIOS chưa được xác minh.'),verified_by=COALESCE(NULLIF(verified_by,''),(SELECT reviewed_by FROM compatibility_reviews c WHERE ((c.primary_variant_id=compatibility_rules.left_variant_id AND c.accessory_variant_id=compatibility_rules.right_variant_id) OR (c.primary_variant_id=compatibility_rules.right_variant_id AND c.accessory_variant_id=compatibility_rules.left_variant_id)) AND c.status='Đã xác minh · cho phép' ORDER BY c.seq DESC LIMIT 1),CASE WHEN is_approved=1 THEN 'DEMO_RULESET' END),verified_at=COALESCE(verified_at,(SELECT reviewed_at FROM compatibility_reviews c WHERE ((c.primary_variant_id=compatibility_rules.left_variant_id AND c.accessory_variant_id=compatibility_rules.right_variant_id) OR (c.primary_variant_id=compatibility_rules.right_variant_id AND c.accessory_variant_id=compatibility_rules.left_variant_id)) AND c.status='Đã xác minh · cho phép' ORDER BY c.seq DESC LIMIT 1))");
        BACKFILL.put("inventory_balances","UPDATE inventory_balances SET warehouse_code=warehouse");
        BACKFILL.put("inventory_units","UPDATE inventory_units SET unit_id=serial,variant_id=product_id,serial_imei=serial,unit_status=status");
        BACKFILL.put("shopping_carts","UPDATE shopping_carts SET cart_id=customer_id,session_key=COALESCE(NULLIF(session_key,''),'CUSTOMER:'||customer_id),cart_status=COALESCE(NULLIF(cart_status,''),'OPEN')");
        BACKFILL.put("cart_items","UPDATE cart_items SET cart_id=customer_id,variant_id=product_id,unit_price_snapshot=CASE WHEN unit_price_snapshot IS NULL OR unit_price_snapshot=0 THEN COALESCE((SELECT NULLIF(sale_price,0) FROM products WHERE id=cart_items.product_id),(SELECT price FROM products WHERE id=cart_items.product_id),0) ELSE unit_price_snapshot END");
        BACKFILL.put("sales_orders","UPDATE sales_orders SET order_id=id,order_number=number,order_status=status,total_amount=total,currency_code='VND',tax_amount=tax");
        BACKFILL.put("order_items","UPDATE order_items SET line_total=quantity*unit_price-COALESCE(discount_amount,0),reservation_state=CASE WHEN reserved_qty>0 THEN 'RESERVED' WHEN allocated_qty>=quantity THEN 'ALLOCATED' ELSE 'OPEN' END,reservation_expires_at=CASE WHEN reserved_qty>0 THEN (SELECT reconciliation_due_at FROM sales_orders WHERE id=order_items.order_id) ELSE NULL END");
        BACKFILL.put("warranties","UPDATE warranties SET warranty_id=serial,allocation_id=serial,coverage_type=COALESCE(NULLIF(coverage_type,''),'STANDARD'),warranty_status=status");
        BACKFILL.put("rma_cases","UPDATE rma_cases SET rma_id=id,warranty_id=serial,problem_description=problem,reason_code=COALESCE(NULLIF(reason_code,''),CASE type WHEN 'Bảo hành' THEN 'WARRANTY_CLAIM' WHEN 'Sửa chữa tính phí' THEN 'PAID_REPAIR' ELSE 'OTHER' END),rma_status=status,closed_at=CASE WHEN status IN ('Hoàn tất','Từ chối') THEN updated_at ELSE NULL END");
        BACKFILL.put("interaction_events","UPDATE interaction_events SET event_id=CAST(seq AS TEXT),event_context=detail");
        BACKFILL.put("recommendation_impressions","UPDATE recommendation_impressions SET impression_id=id,primary_variant_id=primary_id,accessory_variant_id=accessory_id,generated_at=shown_at");
    }

    private static void fields(String table,Field... fields) { FIELDS.put(table,List.of(fields)); }

    static void migrate(Connection db,Path path,boolean hasState,DesignSchemaMigration.Validation validate) throws Exception {
        boolean complete=true;
        for(var entry:FIELDS.entrySet()) for(Field field:entry.getValue()) if(!hasColumn(db,entry.getKey(),field.name())) complete=false;
        if(complete) {
            db.setAutoCommit(false);
            try(Statement s=db.createStatement()) {
                installRelationshipTriggers(s);
                refresh(db);
                installDesignIndexes(s);
                validateDesignKeys(s);
                try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { if(r.next())throw new SQLException("Invalid reference in design fields: "+r.getString(1)); }
                validate.run();db.commit();
            }
            catch(Exception ex) { db.rollback();throw ex; }
            finally { db.setAutoCommit(true); }
            return;
        }
        if(hasState) try(PreparedStatement p=db.prepareStatement("VACUUM INTO ?")) {
            p.setString(1,path.resolveSibling(path.getFileName()+".pre-design-fields-"+System.currentTimeMillis()+".bak").toString());p.execute();
        }
        db.setAutoCommit(false);
        try(Statement s=db.createStatement()) {
            for(var entry:FIELDS.entrySet()) for(Field field:entry.getValue()) if(!hasColumn(db,entry.getKey(),field.name()))
                s.execute("ALTER TABLE "+entry.getKey()+" ADD COLUMN "+field.name()+" "+field.type()+Objects.toString(field.reference(),""));
            for(String table:List.of("customers","products","product_variants","compatibility_rules","inventory_units","shopping_carts","sales_orders","order_items","unit_allocations","payments","shipments","warranties","rma_cases","interaction_events","recommendation_impressions")) {
                String key=switch(table) {
                    case "customers"->"customer_id"; case "products"->"product_id"; case "product_variants"->"variant_id";
                    case "compatibility_rules"->"rule_id";case "inventory_units"->"unit_id";case "shopping_carts"->"cart_id";
                    case "sales_orders"->"order_id";case "order_items"->"order_item_id";case "unit_allocations"->"allocation_id";
                    case "payments"->"payment_id";case "shipments"->"shipment_id";case "warranties"->"warranty_id";
                    case "rma_cases"->"rma_id";case "interaction_events"->"event_id";default->"impression_id";
                };
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS "+table+"_design_key ON "+table+"("+key+")");
            }
            refresh(db);
            installDesignIndexes(s);
            installRelationshipTriggers(s);
            for(String table:List.of("cart_items","compatibility_rules","inventory_units","inventory_balances","order_items","unit_allocations","payments","shipments","warranties","rma_cases","interaction_events","recommendation_impressions"))
                s.execute("CREATE INDEX IF NOT EXISTS "+table+"_design_refs ON "+table+"("+firstReference(table)+")");
            validateDesignKeys(s);
            try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { if(r.next()) throw new SQLException("Invalid reference after design field migration: "+r.getString(1)); }
            validate.run();db.commit();
        } catch(Exception ex) { db.rollback();throw ex; }
        finally { db.setAutoCommit(true); }
    }

    static void refresh(Connection db) throws SQLException {
        backfillOrderItemDiscounts(db);
        try(Statement s=db.createStatement()) { for(String sql:BACKFILL.values()) s.executeUpdate(sql); }
        backfillOperationalDates(db);
        backfillInteractionMetadata(db);
        fillRemainingDesignValues(db);
        List<String> ids=new ArrayList<>();
        try(Statement q=db.createStatement(); ResultSet rows=q.executeQuery("SELECT id FROM product_variants ORDER BY id")) { while(rows.next()) ids.add(rows.getString(1)); }
        try(PreparedStatement specs=db.prepareStatement("SELECT spec_key,spec_value FROM product_specs WHERE product_id=? ORDER BY spec_key");
            PreparedStatement update=db.prepareStatement("UPDATE product_variants SET technical_specs=? WHERE id=?")) {
            for(String id:ids) { specs.setString(1,id);
                StringBuilder json=new StringBuilder("{"); boolean first=true;
                try(ResultSet values=specs.executeQuery()) { while(values.next()) {
                    if(!first)json.append(',');first=false;
                    json.append(quote(values.getString(1))).append(':').append(quote(values.getString(2)));
                }}
                json.append('}');update.setString(1,json.toString());update.setString(2,id);update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void backfillOperationalDates(Connection db) throws SQLException {
        String now=java.time.LocalDateTime.now().toString();
        try(PreparedStatement p=db.prepareStatement("UPDATE customers SET created_at=COALESCE(created_at,?),updated_at=COALESCE(updated_at,created_at,?)")) {
            p.setString(1,now);p.setString(2,now);p.executeUpdate();
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE products SET created_at=COALESCE(created_at,?)")) {
            p.setString(1,now);p.executeUpdate();
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE product_variants SET updated_at=COALESCE(updated_at,?)")) {
            p.setString(1,now);p.executeUpdate();
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE inventory_balances SET updated_at=COALESCE(updated_at,?)")) {
            p.setString(1,now);p.executeUpdate();
        }
        // Existing authorizations have an authoritative time; other historical rows use the migration record time.
        try(PreparedStatement p=db.prepareStatement("UPDATE payments SET updated_at=COALESCE(updated_at,authorized_at,?)")) {
            p.setString(1,now);p.executeUpdate();
        }
        // Mark unverifiable legacy lots explicitly and use the known warehouse code as coarse location.
        try(Statement s=db.createStatement()) {
            s.executeUpdate("UPDATE inventory_units SET batch_number=COALESCE(NULLIF(batch_number,''),'LEGACY-UNVERIFIED'),"
                    +"warehouse_location=COALESCE(NULLIF(warehouse_location,''),(SELECT COALESCE(NULLIF(b.warehouse_code,''),NULLIF(b.warehouse,'')) "
                    +"FROM inventory_balances b WHERE b.variant_id=inventory_units.variant_id LIMIT 1),'UNASSIGNED')");
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE shopping_carts SET created_at=COALESCE(created_at,?),updated_at=COALESCE(updated_at,created_at,?)")) {
            p.setString(1,now);p.setString(2,now);p.executeUpdate();
        }
    }

    private record LegacyEvent(int seq,String type,String detail,String customerId,String source,String consent,
                               String query,String filters,String sortBy,Integer page) { }
    private static void backfillInteractionMetadata(Connection db) throws SQLException {
        List<LegacyEvent> events=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT seq,event_type,detail,customer_id,event_source,consent_scope,search_query,filters,sort_by,page FROM interaction_events WHERE event_source IS NULL OR (event_type LIKE 'search_%' AND search_query IS NULL)")) {
            while(r.next()) {
                String type=r.getString(2),detail=r.getString(3);
                String query=r.getString(7),filters=r.getString(8),sortBy=r.getString(9);Integer page=r.getObject(10)==null?null:r.getInt(10);
                if(type!=null && type.startsWith("search_")) {
                    if(query==null) { var matcher=java.util.regex.Pattern.compile("query=\\\"([^\\\"]*)\\\"").matcher(Objects.toString(detail,""));if(matcher.find())query=matcher.group(1); }
                    if(filters==null) { var matcher=java.util.regex.Pattern.compile("(?:^| · )filters=([^·]*)").matcher(Objects.toString(detail,""));if(matcher.find())filters=matcher.group(1).trim(); }
                    if(!"search_filter_change".equals(type)) { if(sortBy==null)sortBy="RELEVANCE";if(page==null)page=1; }
                }
                events.add(new LegacyEvent(r.getInt(1),type,detail,r.getString(4),r.getString(5),r.getString(6),query,filters,sortBy,page));
            }
        }
        try(PreparedStatement p=db.prepareStatement("UPDATE interaction_events SET event_source=COALESCE(event_source,?),consent_scope=COALESCE(consent_scope,?),search_query=COALESCE(search_query,?),filters=COALESCE(filters,?),sort_by=COALESCE(sort_by,?),page=COALESCE(page,?) WHERE seq=?")) {
            for(LegacyEvent event:events) {
                p.setString(1,event.source()==null?"LEGACY_IMPORT":event.source());
                p.setString(2,event.consent()==null?(event.customerId()==null?"UNKNOWN":"PERSONALIZATION"):event.consent());
                p.setString(3,event.query());p.setString(4,event.filters());p.setString(5,event.sortBy());
                if(event.page()==null)p.setNull(6,Types.INTEGER);else p.setInt(6,event.page());
                p.setInt(7,event.seq());p.addBatch();
            }
            p.executeBatch();
        }
    }

    /** Keep optional columns populated with explicit placeholders when the legacy flow has no value. */
    private static void fillRemainingDesignValues(Connection db) throws SQLException {
        String marker="'"+UNRECORDED_AT+"'";
        String orderStatus=hasColumn(db,"sales_orders","order_status")?"order_status":"status";
        String shipmentStatus=hasColumn(db,"shipments","shipment_status")?"shipment_status":"status";
        List<String> updates=List.of(
                "UPDATE customers SET phone_number=COALESCE(NULLIF(TRIM(phone_number),''),'NOT_PROVIDED')",
                "UPDATE product_variants SET capacity=COALESCE(NULLIF(TRIM(capacity),''),'NOT_SPECIFIED')",
                "UPDATE product_specs SET spec_value=COALESCE(NULLIF(TRIM(spec_value),''),'NOT_SPECIFIED')",
                "UPDATE compatibility_reviews SET reviewed_at=COALESCE(reviewed_at,"+marker+")",
                "UPDATE compatibility_rules SET verified_by=COALESCE(NULLIF(verified_by,''),'SYSTEM_IMPORT'),verified_at=COALESCE(verified_at,"+marker+")",
                "UPDATE inventory_units SET order_id=COALESCE(NULLIF(order_id,''),'UNASSIGNED'),received_at=COALESCE(received_at,"+marker+")",
                "UPDATE cart_items SET added_at=COALESCE(added_at,"+marker+")",
                "UPDATE sales_orders SET sales_verified_at=COALESCE(sales_verified_at,"+marker+"),"
                        +"delivered_at=CASE WHEN "+orderStatus+"='DELIVERED' THEN COALESCE(NULLIF(delivered_at,"+marker+"),created_at,"+marker+") ELSE COALESCE(delivered_at,"+marker+") END,"
                        +"reconciliation_due_at=COALESCE(reconciliation_due_at,"+marker+")",
                "UPDATE order_items SET reservation_expires_at=COALESCE(reservation_expires_at,"+marker+")",
                "UPDATE unit_allocations SET allocated_by=COALESCE(NULLIF(allocated_by,''),'SYSTEM_IMPORT'),"
                        +"scanned_at=COALESCE(NULLIF(scanned_at,"+marker+"),(SELECT COALESCE(NULLIF(o.paid_at,"+marker+"),o.created_at) FROM sales_orders o WHERE o.id=unit_allocations.order_id),"+marker+")",
                "UPDATE payments SET captured_at=COALESCE(captured_at,"+marker+")",
                "UPDATE shipments SET shipped_at=COALESCE(NULLIF(shipped_at,"+marker+"),(SELECT COALESCE(NULLIF(o.paid_at,"+marker+"),o.created_at) FROM sales_orders o WHERE o.id=shipments.order_id),"+marker+"),"
                        +"delivered_at=CASE WHEN "+shipmentStatus+"='DELIVERED' THEN COALESCE(NULLIF(delivered_at,"+marker+"),(SELECT COALESCE(NULLIF(o.delivered_at,"+marker+"),o.created_at) FROM sales_orders o WHERE o.id=shipments.order_id),"+marker+") ELSE COALESCE(delivered_at,"+marker+") END",
                "UPDATE rma_cases SET closed_at=COALESCE(closed_at,updated_at,created_at,"+marker+"),"
                        +"diagnosis=COALESCE(NULLIF(TRIM(diagnosis),''),'NOT_RECORDED'),"
                        +"resolution_notes=COALESCE(NULLIF(TRIM(resolution_notes),''),'NOT_RECORDED'),"
                        +"replacement_parts=COALESCE(NULLIF(TRIM(replacement_parts),''),'NONE')",
                "UPDATE sales_orders SET promotion_code=COALESCE(NULLIF(TRIM(promotion_code),''),'NONE'),"
                        +"tracking_code=COALESCE(NULLIF(TRIM(tracking_code),''),'NOT_ASSIGNED'),"
                        +"carrier=COALESCE(NULLIF(TRIM(carrier),''),'NOT_ASSIGNED'),"
                        +"package_dimensions=COALESCE(NULLIF(TRIM(package_dimensions),''),'NOT_RECORDED'),"
                        +"sales_verified_by=COALESCE(NULLIF(TRIM(sales_verified_by),''),'SYSTEM_IMPORT'),"
                        +"pending_reason=COALESCE(NULLIF(TRIM(pending_reason),''),'NONE')",
                "UPDATE recommendation_impressions SET score=COALESCE(score,0)",
                "UPDATE interaction_events SET anonymous_session_key=COALESCE(NULLIF(anonymous_session_key,''),'CUSTOMER:'||COALESCE(customer_id,'UNKNOWN')),"
                        +"request_id=COALESCE(NULLIF(request_id,''),'LEGACY-'||seq),event_source=COALESCE(NULLIF(event_source,''),'LEGACY_IMPORT'),"
                        +"consent_scope=COALESCE(NULLIF(consent_scope,''),'UNKNOWN'),search_query=COALESCE(search_query,''),filters=COALESCE(filters,'{}'),"
                        +"sort_by=COALESCE(sort_by,'RELEVANCE'),page=COALESCE(page,1)"
        );
        try(Statement s=db.createStatement()) { for(String sql:updates)s.executeUpdate(sql); }
    }

    private static void installRelationshipTriggers(Statement s) throws SQLException {
        s.execute("CREATE TRIGGER IF NOT EXISTS sales_orders_customer_insert BEFORE INSERT ON sales_orders WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'sales order customer does not exist'); END");
        s.execute("CREATE TRIGGER IF NOT EXISTS sales_orders_customer_update BEFORE UPDATE OF customer_id ON sales_orders WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'sales order customer does not exist'); END");
        s.execute("CREATE TRIGGER IF NOT EXISTS warranties_customer_insert BEFORE INSERT ON warranties WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'warranty customer does not exist'); END");
        s.execute("CREATE TRIGGER IF NOT EXISTS warranties_customer_update BEFORE UPDATE OF customer_id ON warranties WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'warranty customer does not exist'); END");
        s.execute("CREATE TRIGGER IF NOT EXISTS rma_customer_insert BEFORE INSERT ON rma_cases WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'RMA customer does not exist'); END");
        s.execute("CREATE TRIGGER IF NOT EXISTS rma_customer_update BEFORE UPDATE OF customer_id ON rma_cases WHEN NOT EXISTS(SELECT 1 FROM customers WHERE id=NEW.customer_id) BEGIN SELECT RAISE(ABORT,'RMA customer does not exist'); END");

        // Populate the design identifiers immediately for legacy writers. These fields
        // have unique indexes and are consumed by the repository on its next read.
        setOnInsert(s,"customers","customer_id","id");
        setOnInsert(s,"products","product_id","id");
        setOnInsert(s,"product_variants","variant_id","id");
        setOnInsert(s,"compatibility_rules","rule_id","left_variant_id||'|'||right_variant_id");
        setOnInsert(s,"inventory_units","unit_id","serial");
        setOnInsert(s,"shopping_carts","cart_id","customer_id");
        setOnInsert(s,"sales_orders","order_id","id");
        setOnInsert(s,"warranties","warranty_id","serial");
        setOnInsert(s,"rma_cases","rma_id","id");
        setOnInsert(s,"interaction_events","event_id","CAST(seq AS TEXT)");
        setOnInsert(s,"recommendation_impressions","impression_id","id");

        // The application still reads a few established column names. Keep updates made
        // through the report's field names visible to those existing domain/repository paths.
        mirror(s,"products","product_name","name");
        mirror(s,"customers","full_name","name");
        mirror(s,"products","brand_name","brand");
        mirror(s,"products","is_active","active");
        mirror(s,"product_variants","is_active","active");
        mirror(s,"inventory_units","serial_imei","serial");
        mirror(s,"inventory_units","variant_id","product_id");
        mirror(s,"inventory_units","unit_status","status");
        mirror(s,"inventory_balances","warehouse_code","warehouse");
        mirror(s,"cart_items","cart_id","customer_id");
        mirror(s,"cart_items","variant_id","product_id");
        mirror(s,"sales_orders","order_number","number");
        mirror(s,"sales_orders","order_status","status");
        mirror(s,"sales_orders","total_amount","total");
        mirror(s,"sales_orders","tax_amount","tax");
        mirror(s,"compatibility_rules","primary_variant_id","left_variant_id");
        mirror(s,"compatibility_rules","accessory_variant_id","right_variant_id");
        mirror(s,"warranties","warranty_status","status");
        mirror(s,"rma_cases","rma_status","status");
        mirror(s,"interaction_events","event_context","detail");
    }

    private static void mirror(Statement s,String table,String designField,String runtimeField) throws SQLException {
        String value="COALESCE(NEW."+designField+",NEW."+runtimeField+")";
        s.execute("CREATE TRIGGER IF NOT EXISTS design_"+table+"_"+designField+"_insert AFTER INSERT ON "+table
                +" BEGIN UPDATE "+table+" SET "+designField+"="+value+","+runtimeField+"="+value+" WHERE rowid=NEW.rowid; END");
        s.execute("CREATE TRIGGER IF NOT EXISTS design_"+table+"_"+designField+"_to_runtime AFTER UPDATE OF "+designField+" ON "+table
                +" WHEN NEW."+designField+" IS NOT OLD."+designField
                +" BEGIN UPDATE "+table+" SET "+runtimeField+"=NEW."+designField+" WHERE rowid=NEW.rowid; END");
        s.execute("CREATE TRIGGER IF NOT EXISTS runtime_"+table+"_"+runtimeField+"_to_design AFTER UPDATE OF "+runtimeField+" ON "+table
                +" WHEN NEW."+runtimeField+" IS NOT OLD."+runtimeField
                +" BEGIN UPDATE "+table+" SET "+designField+"=NEW."+runtimeField+" WHERE rowid=NEW.rowid; END");
    }

    private static void setOnInsert(Statement s,String table,String designField,String legacyExpression) throws SQLException {
        s.execute("CREATE TRIGGER IF NOT EXISTS design_"+table+"_"+designField+"_insert_key AFTER INSERT ON "+table
                +" WHEN NEW."+designField+" IS NULL BEGIN UPDATE "+table+" SET "+designField+"="+legacyExpression+" WHERE rowid=NEW.rowid; END");
    }

    private static void installDesignIndexes(Statement s) throws SQLException {
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS inventory_units_serial_imei_unique ON inventory_units(serial_imei)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS shopping_carts_session_key_unique ON shopping_carts(session_key)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS sales_orders_order_number_unique ON sales_orders(order_number)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS cart_items_design_key ON cart_items(cart_id,variant_id)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS unit_allocations_unit_unique ON unit_allocations(unit_id)");
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS warranties_allocation_unique ON warranties(allocation_id)");
        for(String[] index:new String[][]{
                {"cart_items_variant_ref","cart_items","variant_id"},
                {"compatibility_rules_accessory_ref","compatibility_rules","accessory_variant_id"},
                {"order_items_variant_ref","order_items","variant_id"},
                {"unit_allocations_unit_ref","unit_allocations","unit_id"},
                {"unit_allocations_variant_ref","unit_allocations","variant_id"},
                {"sales_orders_customer_ref","sales_orders","customer_id"},
                {"warranties_customer_ref","warranties","customer_id"},
                {"rma_cases_customer_ref","rma_cases","customer_id"},
                {"interaction_events_variant_ref","interaction_events","variant_id"},
                {"recommendation_impressions_customer_ref","recommendation_impressions","customer_id"},
                {"recommendation_impressions_accessory_ref","recommendation_impressions","accessory_variant_id"}
        }) s.execute("CREATE INDEX IF NOT EXISTS "+index[0]+" ON "+index[1]+"("+index[2]+")");
    }

    private static void validateDesignKeys(Statement s) throws SQLException {
        for(String[] key:new String[][]{
                {"customers","customer_id"},{"products","product_id"},{"product_variants","variant_id"},
                {"compatibility_rules","rule_id"},{"compatibility_rules","primary_variant_id"},{"compatibility_rules","accessory_variant_id"},
                {"inventory_balances","variant_id"},{"inventory_units","unit_id"},{"inventory_units","variant_id"},{"inventory_units","serial_imei"},{"shopping_carts","cart_id"},
                {"shopping_carts","session_key"},{"sales_orders","order_id"},{"sales_orders","order_number"},
                {"cart_items","cart_id"},{"cart_items","variant_id"},{"order_items","order_item_id"},{"order_items","order_id"},{"order_items","variant_id"},
                {"unit_allocations","allocation_id"},{"unit_allocations","order_item_id"},{"unit_allocations","unit_id"},{"unit_allocations","variant_id"},
                {"payments","payment_id"},{"payments","order_id"},{"shipments","shipment_id"},{"shipments","order_id"},
                {"warranties","warranty_id"},{"warranties","allocation_id"},{"rma_cases","rma_id"},{"rma_cases","warranty_id"},
                {"interaction_events","event_id"},{"recommendation_impressions","impression_id"},
                {"recommendation_impressions","primary_variant_id"},{"recommendation_impressions","accessory_variant_id"}
        }) try(ResultSet rows=s.executeQuery("SELECT 1 FROM "+key[0]+" WHERE "+key[1]+" IS NULL OR TRIM(CAST("+key[1]+" AS TEXT))='' LIMIT 1")) {
            if(rows.next()) throw new SQLException("Missing design key: "+key[0]+"."+key[1]);
        }
    }

    private record LegacyLine(String orderId,String variantId,int quantity,long unitPrice,long orderDiscount,long orderSubtotal) { }
    private static void backfillOrderItemDiscounts(Connection db) throws SQLException {
        List<LegacyLine> lines=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT i.order_id,i.variant_id,i.quantity,i.unit_price,COALESCE(o.discount,0),COALESCE(o.subtotal,0) FROM order_items i JOIN sales_orders o ON o.id=i.order_id WHERE i.discount_amount IS NULL ORDER BY i.order_id,i.variant_id")) {
            while(r.next()) lines.add(new LegacyLine(r.getString(1),r.getString(2),r.getInt(3),r.getLong(4),r.getLong(5),r.getLong(6)));
        }
        Map<String,List<LegacyLine>> grouped=new LinkedHashMap<>();
        for(LegacyLine line:lines)grouped.computeIfAbsent(line.orderId(),k->new ArrayList<>()).add(line);
        try(PreparedStatement p=db.prepareStatement("UPDATE order_items SET discount_amount=?,line_total=? WHERE order_id=? AND variant_id=? AND discount_amount IS NULL")) {
            for(List<LegacyLine> order:grouped.values()) {
                long remainingDiscount=order.get(0).orderDiscount();long remainingSubtotal=order.get(0).orderSubtotal();
                for(int i=0;i<order.size();i++) {
                    LegacyLine line=order.get(i);long gross=Math.multiplyExact((long)line.quantity(),line.unitPrice());
                    long discount=i==order.size()-1?remainingDiscount:remainingSubtotal<=0?0
                            :java.math.BigInteger.valueOf(remainingDiscount).multiply(java.math.BigInteger.valueOf(gross))
                                    .divide(java.math.BigInteger.valueOf(remainingSubtotal)).longValueExact();
                    discount=Math.min(gross,Math.max(0,discount));remainingDiscount-=discount;remainingSubtotal-=gross;
                    p.setLong(1,discount);p.setLong(2,gross-discount);p.setString(3,line.orderId());p.setString(4,line.variantId());p.addBatch();
                }
            }
            p.executeBatch();
        }
    }

    private static String quote(String value) {
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) { case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<0x20)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);} }
        }
        return out.append('"').toString();
    }

    private static String firstReference(String table) { return switch(table) {
        case "cart_items"->"cart_id";case "compatibility_rules"->"primary_variant_id";case "inventory_units","inventory_balances"->"variant_id";
        case "order_items"->"order_id";case "unit_allocations"->"order_item_id";case "payments","shipments"->"order_id";
        case "warranties"->"allocation_id";case "rma_cases"->"warranty_id";case "interaction_events"->"customer_id";default->"primary_variant_id";
    }; }
    private static boolean hasColumn(Connection db,String table,String column) throws SQLException {
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next()) if(column.equals(r.getString("name"))) return true;return false;
        }
    }
}
