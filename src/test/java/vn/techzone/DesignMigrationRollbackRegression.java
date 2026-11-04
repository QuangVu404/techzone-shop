package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;
import java.nio.file.*;
import java.sql.*;

/** Confirms migration fails atomically when a disposable legacy database has broken references. */
public final class DesignMigrationRollbackRegression {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Pass an isolated legacy SQLite backup under target.");
        Path path=Path.of(args[0]).toAbsolutePath().normalize(),allowed=Path.of("target").toAbsolutePath().normalize();
        if(!path.startsWith(allowed) || !Files.isRegularFile(path))throw new IllegalArgumentException("Test requires a disposable file under target.");
        Class.forName("org.sqlite.JDBC");long revision;
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SELECT revision FROM app_state WHERE id=1")) { if(!r.next())throw new AssertionError("Missing fixture");revision=r.getLong(1); }
            s.execute("PRAGMA foreign_keys=OFF");
            s.executeUpdate("INSERT INTO product_specs(product_id,spec_key,spec_value) VALUES('TEST-MISSING-VARIANT','rollback-test','invalid')");
        }
        TechZoneStore seed=new TechZoneStore();boolean failed=false;
        try { try(SqliteStore ignored=new SqliteStore(path,seed)) { } }
        catch(SQLException expected) { failed=true; }
        finally { seed.closeRepository(); }
        if(!failed)throw new AssertionError("Migration should reject a broken foreign key");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='product_variants'")) {
                if(!r.next() || r.getInt(1)!=0)throw new AssertionError("Partial schema survived rollback");
            }
            try(ResultSet r=s.executeQuery("SELECT revision FROM app_state WHERE id=1")) { if(!r.next() || r.getLong(1)!=revision)throw new AssertionError("Revision changed on failure"); }
            try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM product_specs WHERE product_id='TEST-MISSING-VARIANT'")) {
                if(!r.next() || r.getInt(1)!=1)throw new AssertionError("Rollback did not preserve original fixture");
            }
        }
        try(var files=Files.list(path.getParent())) {
            if(files.noneMatch(p->p.getFileName().toString().startsWith(path.getFileName()+".pre-design-")))throw new AssertionError("Pre-migration backup missing");
        }
        System.out.println("Design migration rollback: inconsistent data rejected; schema, revision and backup preserved");
    }
}
