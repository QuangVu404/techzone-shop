package vn.techzone.backend;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

/** Adds traceable design identifiers to existing fulfillment records without discarding legacy data. */
final class FulfillmentDesignMigration {
    private FulfillmentDesignMigration() { }

    static void migrate(Connection db, Path path, boolean hasState, DesignSchemaMigration.Validation validate) throws Exception {
        if (hasColumn(db,"unit_allocations","allocation_id")) {
            for (String[] required : new String[][]{{"order_items","order_item_id"},{"unit_allocations","order_item_id"},
                    {"payments","payment_id"},{"shipments","shipment_id"}})
                if (!hasColumn(db,required[0],required[1])) throw new SQLException("Incomplete fulfillment design migration");
            return;
        }
        if (hasState) try (PreparedStatement p=db.prepareStatement("VACUUM INTO ?")) {
            p.setString(1,path.resolveSibling(path.getFileName()+".pre-fulfillment-"+System.currentTimeMillis()+".bak").toString());
            p.execute();
        }
        db.setAutoCommit(false);
        try (Statement s=db.createStatement()) {
            s.execute("ALTER TABLE order_items ADD COLUMN order_item_id TEXT");
            s.execute("UPDATE order_items SET order_item_id=length(order_id)||':'||order_id||variant_id");
            s.execute("CREATE UNIQUE INDEX order_items_design_id ON order_items(order_item_id)");
            s.execute("CREATE TRIGGER order_items_design_insert AFTER INSERT ON order_items WHEN NEW.order_item_id IS NULL BEGIN UPDATE order_items SET order_item_id=length(NEW.order_id)||':'||NEW.order_id||NEW.variant_id WHERE rowid=NEW.rowid; END");
            for (String sql : List.of(
                    "ALTER TABLE unit_allocations ADD COLUMN allocation_id TEXT",
                    "ALTER TABLE unit_allocations ADD COLUMN order_item_id TEXT REFERENCES order_items(order_item_id)",
                    "ALTER TABLE unit_allocations ADD COLUMN unit_id TEXT REFERENCES inventory_units(serial)",
                    "ALTER TABLE unit_allocations ADD COLUMN variant_id TEXT REFERENCES product_variants(id)",
                    "ALTER TABLE unit_allocations ADD COLUMN allocation_status TEXT",
                    "ALTER TABLE unit_allocations ADD COLUMN allocated_by TEXT",
                    "ALTER TABLE unit_allocations ADD COLUMN scanned_at TEXT",
                    "ALTER TABLE payments ADD COLUMN payment_id TEXT",
                    "ALTER TABLE payments ADD COLUMN payment_method TEXT",
                    "ALTER TABLE payments ADD COLUMN idempotency_key TEXT",
                    "ALTER TABLE payments ADD COLUMN amount INTEGER",
                    "ALTER TABLE payments ADD COLUMN currency_code TEXT",
                    "ALTER TABLE payments ADD COLUMN captured_at TEXT",
                    "ALTER TABLE payments ADD COLUMN updated_at TEXT",
                    "ALTER TABLE shipments ADD COLUMN shipment_id TEXT",
                    "ALTER TABLE shipments ADD COLUMN carrier_name TEXT",
                    "ALTER TABLE shipments ADD COLUMN shipped_at TEXT",
                    "ALTER TABLE shipments ADD COLUMN shipment_status TEXT",
                    "ALTER TABLE shipments ADD COLUMN delivered_at TEXT")) s.execute(sql);
            s.execute("UPDATE unit_allocations SET allocation_id=serial,order_item_id=length(order_id)||':'||order_id||product_id,unit_id=serial,variant_id=product_id,allocation_status='ALLOCATED'");
            s.execute("UPDATE payments SET payment_id=order_id,payment_method=method,idempotency_key=(SELECT idempotency_key FROM sales_orders WHERE id=payments.order_id),amount=(SELECT total FROM sales_orders WHERE id=payments.order_id),currency_code='VND'");
            s.execute("UPDATE shipments SET shipment_id=order_id,carrier_name=carrier,shipment_status=(SELECT status FROM sales_orders WHERE id=shipments.order_id),delivered_at=(SELECT delivered_at FROM sales_orders WHERE id=shipments.order_id)");
            s.execute("CREATE UNIQUE INDEX unit_allocations_design_id ON unit_allocations(allocation_id)");
            s.execute("CREATE UNIQUE INDEX payments_design_id ON payments(payment_id)");
            s.execute("CREATE UNIQUE INDEX shipments_design_id ON shipments(shipment_id)");
            s.execute("CREATE TRIGGER unit_allocations_design_insert AFTER INSERT ON unit_allocations BEGIN UPDATE unit_allocations SET allocation_id=NEW.serial,order_item_id=length(NEW.order_id)||':'||NEW.order_id||NEW.product_id,unit_id=NEW.serial,variant_id=NEW.product_id,allocation_status='ALLOCATED' WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER payments_design_insert AFTER INSERT ON payments BEGIN UPDATE payments SET payment_id=NEW.order_id,payment_method=NEW.method,idempotency_key=(SELECT idempotency_key FROM sales_orders WHERE id=NEW.order_id),amount=(SELECT total FROM sales_orders WHERE id=NEW.order_id),currency_code='VND' WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER shipments_design_insert AFTER INSERT ON shipments BEGIN UPDATE shipments SET shipment_id=NEW.order_id,carrier_name=NEW.carrier,shipment_status=(SELECT status FROM sales_orders WHERE id=NEW.order_id),delivered_at=(SELECT delivered_at FROM sales_orders WHERE id=NEW.order_id) WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER payments_design_method AFTER UPDATE OF method ON payments WHEN NEW.method IS NOT NEW.payment_method BEGIN UPDATE payments SET payment_method=NEW.method WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER payments_legacy_method AFTER UPDATE OF payment_method ON payments WHEN NEW.method IS NOT NEW.payment_method BEGIN UPDATE payments SET method=NEW.payment_method WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER shipments_design_carrier AFTER UPDATE OF carrier ON shipments WHEN NEW.carrier IS NOT NEW.carrier_name BEGIN UPDATE shipments SET carrier_name=NEW.carrier WHERE rowid=NEW.rowid; END");
            s.execute("CREATE TRIGGER shipments_legacy_carrier AFTER UPDATE OF carrier_name ON shipments WHEN NEW.carrier IS NOT NEW.carrier_name BEGIN UPDATE shipments SET carrier=NEW.carrier_name WHERE rowid=NEW.rowid; END");
            try (ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) {
                if (r.next()) throw new SQLException("Invalid fulfillment reference in " + r.getString(1));
            }
            validate.run();
            db.commit();
        } catch (Exception ex) { db.rollback(); throw ex; }
        finally { db.setAutoCommit(true); }
    }

    private static boolean hasColumn(Connection db,String table,String column) throws SQLException {
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while (r.next()) if (column.equals(r.getString("name"))) return true;
            return false;
        }
    }
}
