package vn.techzone;

import java.nio.file.*;
import java.sql.*;
import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;

/** Run only against a disposable pre-fulfillment database copy. */
public final class FulfillmentMigrationRollbackRegression {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Pass a disposable database under target");
        Path path=Path.of(args[0]).toAbsolutePath().normalize();
        if(!path.startsWith(Path.of("target").toAbsolutePath().normalize()) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("Database must be a disposable file under target");
        Class.forName("org.sqlite.JDBC");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path); Statement s=c.createStatement()) {
            s.execute("PRAGMA foreign_keys=OFF");
            if(s.executeUpdate("UPDATE unit_allocations SET product_id='missing-variant' WHERE serial=(SELECT serial FROM unit_allocations LIMIT 1)")!=1)
                throw new AssertionError("Expected an allocation fixture");
        }
        TechZoneStore seed=new TechZoneStore();boolean failed=false;
        try { try(SqliteStore ignored=new SqliteStore(path,seed)) { } }
        catch(SQLException expected) { failed=true; }
        finally { seed.closeRepository(); }
        if(!failed) throw new AssertionError("Migration should reject inconsistent references");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("PRAGMA table_info(unit_allocations)")) {
                while(r.next()) if("allocation_id".equals(r.getString("name"))) throw new AssertionError("Migration left a partial column");
            }
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM unit_allocations WHERE product_id='missing-variant'")) {
                if(!r.next() || r.getInt(1)!=1) throw new AssertionError("Original fixture changed after rollback");
            }
        }
        try(var files=Files.list(path.getParent())) {
            if(files.noneMatch(p->p.getFileName().toString().startsWith(path.getFileName()+".pre-fulfillment-")))
                throw new AssertionError("Missing pre-fulfillment backup");
        }
        System.out.println("Fulfillment migration rollback: broken link rejected, original schema and backup preserved");
    }
}
