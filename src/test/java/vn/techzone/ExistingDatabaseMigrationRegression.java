package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Run only against a disposable SQLite backup, never the user's live database. */
public final class ExistingDatabaseMigrationRegression {
    private static final List<String> TABLES=List.of("app_state","products","product_specs","inventory","inventory_units","sales_orders","order_lines","unit_allocations","warranties","rma_cases","customers","shopping_carts","cart_items","compatibility_rules","interaction_events","recommendation_impressions","payments","shipments","market_offers");
    private static List<String> columns(Connection c,String table) throws SQLException {
        List<String> result=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM \""+table+"\" LIMIT 0")) {
            for(int i=1;i<=r.getMetaData().getColumnCount();i++)result.add(r.getMetaData().getColumnName(i));
        }
        return result;
    }
    private static String digest(Connection c,String table,List<String> columns) throws Exception {
        List<String> rows=new ArrayList<>();
        String selected=columns.stream().map(name->"\""+name.replace("\"","\"\"")+"\"").collect(java.util.stream.Collectors.joining(","));
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT "+selected+" FROM \""+table+"\"")) {
            int count=r.getMetaData().getColumnCount();
            while(r.next()) {
                StringBuilder line=new StringBuilder();
                for(int i=1;i<=count;i++) {
                    Object value=r.getObject(i);
                    String part=value instanceof byte[] ? HexFormat.of().formatHex((byte[])value) : String.valueOf(value);
                    line.append(part.length()).append(':').append(part).append('|');
                }
                rows.add(line.toString());
            }
        }
        Collections.sort(rows); MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String row:rows)hash.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash.digest());
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Pass an isolated SQLite backup under target.");
        Path path=Path.of(args[0]).toAbsolutePath().normalize(),allowed=Path.of("target").toAbsolutePath().normalize();
        if(!path.startsWith(allowed) || !Files.isRegularFile(path))throw new IllegalArgumentException("Test requires a file under target.");
        Class.forName("org.sqlite.JDBC");Map<String,String> before=new LinkedHashMap<>();Map<String,List<String>> columnMap=new LinkedHashMap<>();
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)) {
            for(String table:TABLES) { columnMap.put(table,columns(c,table));before.put(table,digest(c,table,columnMap.get(table))); }
        }
        TechZoneStore seed=new TechZoneStore();
        try(SqliteStore store=new SqliteStore(path,seed)) {
            var state=store.read();
            if(state==null)throw new AssertionError("Existing state disappeared");
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=c.createStatement()) {
                for(String table:TABLES)if(!before.get(table).equals(digest(c,table,columnMap.get(table))))throw new AssertionError("Data changed during schema migration: "+table);
                try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")) { if(r.next())throw new AssertionError("Foreign-key mismatch after migration"); }
                try(ResultSet r=s.executeQuery("SELECT (SELECT COUNT(*) FROM products),(SELECT COUNT(*) FROM product_variants)")) {
                    if(!r.next() || r.getInt(1)!=r.getInt(2))throw new AssertionError("Legacy SKUs did not migrate one-to-one");
                }
            }
            TechZoneStore loaded=SqliteStore.decodeSession(state.bytes());
            try { store.save(loaded,state.revision()); }
            finally { loaded.closeRepository(); }
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)) {
                for(String table:TABLES) if(!table.equals("app_state") && !before.get(table).equals(digest(c,table,columnMap.get(table))))
                    throw new AssertionError("Data changed on save after migration: "+table);
            }
        } finally { seed.closeRepository(); }
        try(var paths=Files.list(path.getParent())) {
            if(paths.noneMatch(p->p.getFileName().toString().startsWith(path.getFileName()+".pre-design-")))
                throw new AssertionError("Pre-migration backup was not created");
        }
        System.out.println("Existing data: all "+TABLES.size()+" tables preserved; design migration, foreign keys and backup passed");
    }
}
