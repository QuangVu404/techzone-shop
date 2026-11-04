package vn.techzone.backend;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Transactional transition from the legacy one-row-per-SKU schema to product/variant tables. */
final class DesignSchemaMigration {
    private DesignSchemaMigration() { }
    @FunctionalInterface interface Validation { void run() throws Exception; }
    private static final List<String> VARIANT_REFERENCES=List.of("product_specs","inventory","inventory_units","order_lines",
            "cart_items","compatibility_rules","recommendation_impressions","warranties");

    static void migrate(Connection db,Path dbPath,Validation validation) throws Exception {
        if(tableExists(db,"product_variants")) {
            if(!tableExists(db,"inventory_balances") || !tableExists(db,"order_items"))
                throw new SQLException("Cơ sở dữ liệu có schema sản phẩm dở dang; không tự thay đổi dữ liệu.");
            return;
        }
        // Preserve the whole database, including supplemental snapshot data, before changing any schema.
        if(hasExistingData(db)) {
            Path backup=dbPath.resolveSibling(dbPath.getFileName()+".pre-design-"+System.currentTimeMillis()+".bak");
            try(PreparedStatement p=db.prepareStatement("VACUUM INTO ?")) { p.setString(1,backup.toString());p.execute(); }
        }
        try(Statement s=db.createStatement()) { s.execute("PRAGMA foreign_keys=OFF");s.execute("PRAGMA legacy_alter_table=OFF"); }
        db.setAutoCommit(false);
        try {
            try(Statement s=db.createStatement()) {
                s.execute("CREATE TABLE product_variants(id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), sku TEXT NOT NULL UNIQUE, variant_name TEXT NOT NULL, color TEXT NOT NULL, price INTEGER NOT NULL CHECK(price>=0), sale_price INTEGER NOT NULL CHECK(sale_price>=0), warranty_months INTEGER NOT NULL CHECK(warranty_months>=0), active INTEGER NOT NULL)");
                s.executeUpdate("INSERT INTO product_variants(id,product_id,sku,variant_name,color,price,sale_price,warranty_months,active) SELECT id,id,sku,name,color,price,sale_price,warranty_months,active FROM products");
                s.execute("CREATE INDEX product_variants_product_idx ON product_variants(product_id)");
            }
            for(String table:VARIANT_REFERENCES) redirectVariantForeignKeys(db,table);
            try(Statement s=db.createStatement()) {
                s.execute("ALTER TABLE inventory RENAME TO inventory_balances");
                s.execute("ALTER TABLE inventory_balances RENAME COLUMN product_id TO variant_id");
                s.execute("ALTER TABLE inventory_balances RENAME COLUMN available TO available_qty");
                s.execute("ALTER TABLE inventory_balances RENAME COLUMN reserved TO reserved_qty");
                s.execute("ALTER TABLE inventory_balances RENAME COLUMN allocated TO allocated_qty");
                s.execute("ALTER TABLE inventory_balances RENAME COLUMN threshold TO reorder_threshold");
                s.execute("ALTER TABLE order_lines RENAME TO order_items");
                s.execute("ALTER TABLE order_items RENAME COLUMN product_id TO variant_id");
                installLegacyViews(s);
                if(!hasColumn(db,"interaction_events","customer_id"))s.execute("ALTER TABLE interaction_events ADD COLUMN customer_id TEXT REFERENCES customers(id)");
                if(!hasColumn(db,"interaction_events","variant_id"))s.execute("ALTER TABLE interaction_events ADD COLUMN variant_id TEXT REFERENCES product_variants(id)");
                s.execute("CREATE INDEX IF NOT EXISTS interaction_customer_idx ON interaction_events(customer_id)");
                s.execute("CREATE INDEX IF NOT EXISTS interaction_variant_idx ON interaction_events(variant_id)");
                try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) {
                    if(r.next()) throw new SQLException("Vi phạm khóa ngoại sau chuyển đổi: "+r.getString(1));
                }
                try(ResultSet r=s.executeQuery("PRAGMA integrity_check")) {
                    if(!r.next() || !"ok".equalsIgnoreCase(r.getString(1))) throw new SQLException("Cơ sở dữ liệu không toàn vẹn sau chuyển đổi.");
                }
            }
            validation.run();
            db.commit();
        } catch(Exception ex) { db.rollback(); throw ex; }
        finally {
            db.setAutoCommit(true);
            try(Statement s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); }
        }
    }

    private static boolean tableExists(Connection db,String name) throws SQLException {
        try(PreparedStatement p=db.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            p.setString(1,name);try(ResultSet r=p.executeQuery()) { return r.next(); }
        }
    }
    private static boolean hasColumn(Connection db,String table,String column) throws SQLException {
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next())if(column.equals(r.getString("name")))return true;
            return false;
        }
    }
    private static boolean hasExistingData(Connection db) throws SQLException {
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM app_state) OR EXISTS(SELECT 1 FROM products)")) {
            return r.next() && r.getInt(1)!=0;
        }
    }
    private static void redirectVariantForeignKeys(Connection db,String table) throws SQLException {
        String ddl;
        try(PreparedStatement p=db.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            p.setString(1,table);try(ResultSet r=p.executeQuery()) { if(!r.next())throw new SQLException("Thiếu bảng "+table);ddl=r.getString(1); }
        }
        String temporary=table+"__design_migration";
        String tablePattern="(?i)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\\\"`\\[]?"+Pattern.quote(table)+"[\\\"`\\]]?";
        String replacement=Pattern.compile(tablePattern).matcher(ddl).replaceFirst("CREATE TABLE \""+temporary+"\"");
        Pattern reference=Pattern.compile("(?i)REFERENCES\\s+[\\\"`\\[]?products[\\\"`\\]]?\\s*\\(\\s*[\\\"`\\[]?id[\\\"`\\]]?\\s*\\)");
        Matcher matcher=reference.matcher(replacement);
        if(replacement.equals(ddl) || !matcher.find())throw new SQLException("Không thể chuyển an toàn khóa ngoại của "+table);
        replacement=matcher.replaceAll("REFERENCES product_variants(id)");
        List<String> objects=new ArrayList<>();
        try(PreparedStatement p=db.prepareStatement("SELECT sql FROM sqlite_master WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL ORDER BY type,name")) {
            p.setString(1,table);try(ResultSet r=p.executeQuery()) { while(r.next())objects.add(r.getString(1)); }
        }
        try(Statement s=db.createStatement()) {
            s.execute(replacement);
            s.executeUpdate("INSERT INTO \""+temporary+"\" SELECT * FROM \""+table+"\"");
            s.execute("DROP TABLE \""+table+"\"");
            s.execute("ALTER TABLE \""+temporary+"\" RENAME TO \""+table+"\"");
            for(String object:objects)s.execute(object);
        }
    }
    private static void installLegacyViews(Statement s) throws SQLException {
        s.execute("CREATE VIEW inventory AS SELECT variant_id AS product_id, available_qty AS available, reserved_qty AS reserved, allocated_qty AS allocated, reorder_threshold AS threshold, warehouse FROM inventory_balances");
        s.execute("CREATE TRIGGER inventory_compat_insert INSTEAD OF INSERT ON inventory BEGIN INSERT INTO inventory_balances(variant_id,available_qty,reserved_qty,allocated_qty,reorder_threshold,warehouse) VALUES(NEW.product_id,NEW.available,NEW.reserved,NEW.allocated,NEW.threshold,NEW.warehouse); END");
        s.execute("CREATE TRIGGER inventory_compat_update INSTEAD OF UPDATE ON inventory BEGIN UPDATE inventory_balances SET variant_id=NEW.product_id,available_qty=NEW.available,reserved_qty=NEW.reserved,allocated_qty=NEW.allocated,reorder_threshold=NEW.threshold,warehouse=NEW.warehouse WHERE variant_id=OLD.product_id; END");
        s.execute("CREATE TRIGGER inventory_compat_delete INSTEAD OF DELETE ON inventory BEGIN DELETE FROM inventory_balances WHERE variant_id=OLD.product_id; END");
        s.execute("CREATE VIEW order_lines AS SELECT order_id,variant_id AS product_id,quantity,unit_price,reserved_qty,allocated_qty FROM order_items");
        s.execute("CREATE TRIGGER order_lines_compat_insert INSTEAD OF INSERT ON order_lines BEGIN INSERT INTO order_items(order_id,variant_id,quantity,unit_price,reserved_qty,allocated_qty) VALUES(NEW.order_id,NEW.product_id,NEW.quantity,NEW.unit_price,NEW.reserved_qty,NEW.allocated_qty); END");
        s.execute("CREATE TRIGGER order_lines_compat_update INSTEAD OF UPDATE ON order_lines BEGIN UPDATE order_items SET order_id=NEW.order_id,variant_id=NEW.product_id,quantity=NEW.quantity,unit_price=NEW.unit_price,reserved_qty=NEW.reserved_qty,allocated_qty=NEW.allocated_qty WHERE order_id=OLD.order_id AND variant_id=OLD.product_id; END");
        s.execute("CREATE TRIGGER order_lines_compat_delete INSTEAD OF DELETE ON order_lines BEGIN DELETE FROM order_items WHERE order_id=OLD.order_id AND variant_id=OLD.product_id; END");
    }
}
