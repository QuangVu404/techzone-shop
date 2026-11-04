package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.*;
import vn.techzone.infrastructure.RemoteStoreRepository;
import vn.techzone.infrastructure.DemoStoreRepository;
import vn.techzone.domain.TechZoneModels.PaymentVerdict;
import java.nio.file.*;
import java.sql.*;
import java.io.*;
import java.net.URI;
import java.util.Properties;
import java.util.Comparator;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Integration checks cover HTTP, persisted SQLite rows and conflicting sessions. */
public final class BackendSqliteRegression {
    public static final class StartupProbe {
        public static void main(String[] args) throws Exception {
            BackendConnection.Session session=BackendConnection.connectOrStart();
            try {
                RemoteStoreRepository remote=new RemoteStoreRepository(session.uri(),session.token());
                TechZoneStore store=remote.loadOrCreate(); store.attachRepository(remote);
                try {
                    store.setThreshold("GPU-4060",18,"test"); if(!store.flushData()) throw new IOException(store.persistenceError());

                }
                finally { store.closeRepository(); }
            } finally { if(session.ownedProcess()!=null) { session.ownedProcess().destroy(); session.ownedProcess().waitFor(10,TimeUnit.SECONDS); } }
        }
    }
    private static void check(boolean b,String message) { if(!b) throw new AssertionError(message); }
    private static RemoteStoreRepository.OperationResponse operation(RemoteStoreRepository remote,String... pairs) throws IOException {
        java.util.Map<String,String> fields=new java.util.LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) fields.put(pairs[i],pairs[i+1]);
        return remote.operation(fields);
    }
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory(Path.of("target"),"backend-db-test-");
        Path path=dir.resolve("test.sqlite"), images=dir.resolve("images");
        TechZoneStore seed=new TechZoneStore(); String orderId;
        try {
            try(SqliteStore db=new SqliteStore(path,seed); CatalogBackend api=new CatalogBackend(SqliteStore.decode(db.read().bytes()),images,false)) {
                api.attachDatabase(db);
                var first=new RemoteStoreRepository(api.baseUri(),api.token());
                var second=new RemoteStoreRepository(api.baseUri(),api.token());
                var searchClient=new CatalogClient(api.baseUri(),api.token());
                var search=searchClient.search("LGA1700 DDR5","Mainboard","LGA1700","DDR5",0,true,java.util.Map.of());
                check(search.ids().contains("MB-B760-D5") && !search.ids().contains("MB-B760-D4"),"backend search enforces explicit filters");
                var contradictory=searchClient.search("DDR4","Mainboard","LGA1700","DDR5",0,true,java.util.Map.of());
                check(contradictory.ids().isEmpty(),"backend search never relaxes contradictory filters");
                TechZoneStore a=first.loadOrCreate(), b=second.loadOrCreate();
                try {
                    int stock=a.available("GPU-4060");
                    a.setInteractionConsent(true); a.recordProductView("GPU-4060");
                    a.recordRecommendationResult("CPU-14600K",a.recommend("CPU-14600K",java.util.Set.of(),3));
                    check(a.createCurrentCustomerRma("SN-TZ-PHONES24-0001","Lỗi màn hình").startsWith("Đã tạo"),"RMA fixture");
                    check(a.addToCart("GPU-4060",1).startsWith("Đã"),"add item");
                    var result=a.checkout("Địa chỉ test","Ví mock","",PaymentVerdict.AUTHORIZED);
                    check(result.confirmed(),"checkout"); orderId=result.order().id;
                    first.save(a);
                    var catalog=new CatalogClient(api.baseUri(),api.token());
                    check(catalog.productJson("GPU-4060").contains("\"available\":"+(stock-1)),"API shows persisted stock");
                    check(catalog.inventoryJson().contains("GPU-4060") && catalog.ordersJson().contains(orderId),"inventory and order API read saved state");
                    try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement q=connection.createStatement()) {
                        try(ResultSet rows=q.executeQuery("SELECT available FROM inventory WHERE product_id='GPU-4060'")) { check(rows.next() && rows.getInt(1)==stock-1,"SQLite inventory row"); }
                        try(PreparedStatement stmt=connection.prepareStatement("SELECT COUNT(*) FROM sales_orders WHERE id=?")) {
                            stmt.setString(1,orderId); try(ResultSet rows=stmt.executeQuery()) { check(rows.next() && rows.getInt(1)==1,"SQLite order row"); }
                        }
                        try(PreparedStatement stmt=connection.prepareStatement("SELECT COUNT(*) FROM order_lines WHERE order_id=?")) {
                            stmt.setString(1,orderId); try(ResultSet rows=stmt.executeQuery()) { check(rows.next() && rows.getInt(1)==1,"SQLite order item row"); }
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM rma_cases WHERE serial='SN-TZ-PHONES24-0001'")) {
                            check(rows.next() && rows.getInt(1)==1,"SQLite RMA row linked to warranty");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM interaction_events WHERE event_type='product_view'")) {
                            check(rows.next() && rows.getInt(1)>0,"permitted interactions persist as relational events");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM compatibility_rules")) {
                            check(rows.next() && rows.getInt(1)>0,"approved compatibility pairs persist as relational rows");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*),COUNT(DISTINCT request_id) FROM recommendation_impressions")) {
                            check(rows.next() && rows.getInt(1)>0 && rows.getInt(2)==1,"ranked recommendation impressions retain one request ID");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM unit_allocations WHERE serial='SN-TZ-PHONES24-0001' AND order_id='ORD-0897'")) {
                            check(rows.next() && rows.getInt(1)==1,"allocated serial persists against the correct order line");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM payments WHERE order_id='ORD-1042' AND payment_status='AUTHORIZED'")) {
                            check(rows.next() && rows.getInt(1)==1,"payment status persists as a relational record");
                        }
                        try(ResultSet rows=q.executeQuery("SELECT COUNT(*) FROM shipments WHERE order_id='ORD-1038'")) {
                            check(rows.next() && rows.getInt(1)==1,"shipment persists as a relational record");
                        }
                    }
                    a.balance("GPU-4060").available=-1;
                    boolean rejected=false; try { first.save(a); } catch(java.io.IOException ex) { rejected=true; }
                    check(rejected,"invalid inventory must reject the entire SQLite transaction");
                    try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement q=connection.createStatement();
                        ResultSet rows=q.executeQuery("SELECT available FROM inventory WHERE product_id='GPU-4060'")) {
                        check(rows.next() && rows.getInt(1)==stock-1,"failed write must preserve committed stock");
                    }
                    a.balance("GPU-4060").available=stock-1;
                    check(a.registerCustomer("Khách API","api@example.com","Password123!".toCharArray()).success(),"register API customer fixture");
                    check(a.authenticateCustomer("api@example.com","Password123!".toCharArray()).success(),"login API customer fixture");
                    String customerId=a.signedInCustomerId();
                    a.addToCart("CPU-14600K",1); int cpuBefore=a.available("CPU-14600K");
                    first.save(a);
                    var invalid=first.checkout("","Ví mock","",PaymentVerdict.AUTHORIZED,"checkout-invalid-key");
                    try { check(!invalid.confirmed() && invalid.orderId().isBlank() && invalid.store().available("CPU-14600K")==cpuBefore,
                            "backend rejects missing shipping address without stock reservation"); }
                    finally { invalid.store().closeRepository(); }
                    var remoteCheckout=first.checkout("Địa chỉ API","Ví mock","",PaymentVerdict.AUTHORIZED,"checkout-api-key");
                    String apiOrderId=remoteCheckout.orderId();
                    TechZoneStore resultStore=remoteCheckout.store();
                    try {
                        check(remoteCheckout.confirmed() && !remoteCheckout.orderId().isBlank(),"backend executes checkout");
                        check(resultStore.signedInCustomerId().equals(customerId) && resultStore.order(remoteCheckout.orderId()).customerId.equals(customerId),"checkout preserves authenticated demo account");
                        check(resultStore.available("CPU-14600K")==cpuBefore-1 && resultStore.cart().isEmpty(),"backend reserves stock and clears cart");
                        var retry=first.checkout("Địa chỉ API","Ví mock","",PaymentVerdict.AUTHORIZED,"checkout-api-key");
                        try {
                            check(retry.orderId().equals(remoteCheckout.orderId()) && retry.store().available("CPU-14600K")==cpuBefore-1,"checkout API idempotency prevents duplicate reservation");
                            retry.store().addToCart("RAM-D5-32",1); first.save(retry.store());
                        }
                        finally { retry.store().closeRepository(); }
                    } finally { resultStore.closeRepository(); }
                    TechZoneStore working=first.loadOrCreate();
                    try {
                        String serial=working.sampleReservedSerial("CPU-14600K",apiOrderId);
                        boolean denied=false;
                        try { operation(first,"operation","allocate","role","CUSTOMER","actor","Khách","order",apiOrderId,"variant","CPU-14600K","serial",serial); }
                        catch(IOException ex) { denied=ex.getMessage().contains("Vai trò"); }
                        check(denied,"customer demo role cannot allocate serial through backend");
                        var allocation=operation(first,"operation","allocate","role","WAREHOUSE","actor","Kho","order",apiOrderId,"variant","CPU-14600K","serial",serial);
                        working.closeRepository(); working=allocation.store();
                        check(working.order(apiOrderId).lines.get(0).serials.contains(serial),"backend allocates scanned serial");
                        var repeat=operation(first,"operation","allocate","role","WAREHOUSE","actor","Kho","order",apiOrderId,"variant","CPU-14600K","serial",serial);
                        working.closeRepository(); working=repeat.store();
                        check(working.order(apiOrderId).lines.get(0).serials.size()==1,"duplicate allocation must not allocate twice");
                        var shipped=operation(first,"operation","ship","role","WAREHOUSE","actor","Kho","order",apiOrderId,"carrier","GHN","weight","1000","dimensions","30 x 20 x 10 cm");
                        working.closeRepository(); working=shipped.store();
                        check(working.order(apiOrderId).status==vn.techzone.domain.TechZoneModels.OrderStatus.SHIPPED,"backend creates shipment");
                        String[] steps={"Đang trung chuyển","Đang giao tới bạn","Đã giao thành công"};
                        for(int i=0;i<steps.length;i++) {
                            var moved=operation(first,"operation","milestone","role","WAREHOUSE","actor","Kho","order",apiOrderId,"callback","API-EVENT-"+i,"milestone",steps[i],"location","HCM");
                            working.closeRepository(); working=moved.store();
                        }
                        check(working.order(apiOrderId).status==vn.techzone.domain.TechZoneModels.OrderStatus.DELIVERED
                                && working.warranty(serial)!=null,"backend delivery activates warranty for allocated unit");
                        String rmaId=working.rmas().get(0).id;
                        var rma=operation(first,"operation","rma_update","role","SUPPORT","actor","Kỹ thuật","rma",rmaId,"status","Đang kiểm tra","assigned","Kỹ thuật","diagnosis","Đối chiếu","parts","","resolution","");
                        working.closeRepository(); working=rma.store();
                        check(working.rma(rmaId).status.equals("Đang kiểm tra"),"backend updates RMA");
                        try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement q=connection.createStatement(); ResultSet violations=q.executeQuery("PRAGMA foreign_key_check")) {
                            check(!violations.next(),"relational foreign keys remain consistent after fulfillment");
                        }
                    } finally { working.closeRepository(); }
                    boolean checkoutConflict=false;
                    try { second.checkout("Địa chỉ","Ví mock","",PaymentVerdict.AUTHORIZED,"checkout-stale-key"); }
                    catch(java.io.IOException ex) { checkoutConflict=ex.getMessage().contains("phiên khác"); }
                    check(checkoutConflict,"backend must reject checkout from stale session");
                    b.addToCart("CPU-14600K",1); boolean conflict=false;
                    try { second.save(b); } catch(java.io.IOException ex) { conflict=ex.getMessage().contains("phiên khác"); }
                    check(conflict,"stale session must not overwrite new order");
                } finally { a.closeRepository(); b.closeRepository(); }
            }
            try(SqliteStore reopened=new SqliteStore(path,seed)) {
                TechZoneStore loaded=SqliteStore.decode(reopened.read().bytes());
                try {
                    check(loaded.order(orderId)!=null,"SQLite restores order after restart");
                    var delivered=loaded.order("ORD-0897");
                    check(delivered!=null && delivered.lines.get(0).serials.contains("SN-TZ-PHONES24-0001")
                            && loaded.warranty("SN-TZ-PHONES24-0001")!=null,
                            "relational reload retains allocated serial and linked warranty");
                    check(loaded.rmas().stream().anyMatch(r -> r.serial.equals("SN-TZ-PHONES24-0001")),"relational reload retains RMA");
                    check(loaded.interactionEvents().stream().anyMatch(e -> e.type.equals("product_view"))
                            && loaded.compatible("CPU-14600K","MB-B760-D5"),"relational reload retains discovery events and compatibility");
                    check(!loaded.recommendationImpressions().isEmpty(),"relational reload retains recommendation impressions");
                    check(loaded.order("ORD-1042").lines.stream().allMatch(line -> line.reservedQuantity==line.quantity),
                            "relational reload retains pending reservation counts");
                    check(loaded.authenticateCustomer("api@example.com","Password123!".toCharArray()).success()
                            && loaded.cart().containsKey("RAM-D5-32"),"relational reload retains password hash and customer cart");
                }
                finally { loaded.closeRepository(); }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement s=c.createStatement()) {
                    s.executeUpdate("UPDATE product_variants SET sale_price=1234567 WHERE id='GPU-4060'");
                    s.executeUpdate("UPDATE product_specs SET spec_value='test-sql' WHERE product_id='GPU-4060' AND spec_key='vram'");
                    s.executeUpdate("UPDATE sales_orders SET shipping_address='Địa chỉ từ SQLite' WHERE id='ORD-1042'");
                    s.executeUpdate("UPDATE rma_cases SET problem='Mô tả từ SQLite' WHERE serial='SN-TZ-PHONES24-0001'");
                    s.executeUpdate("UPDATE customers SET name='Tên từ SQLite' WHERE email='api@example.com'");
                    s.executeUpdate("UPDATE cart_items SET quantity=2 WHERE product_id='RAM-D5-32'");
                    s.executeUpdate("UPDATE payments SET provider_ref='SQL-TOKEN' WHERE order_id='ORD-1042'");
                    s.executeUpdate("UPDATE shipments SET tracking_code='SQL-TRACKING' WHERE order_id='ORD-1038'");
                }
                TechZoneStore sqlState=SqliteStore.decode(reopened.read().bytes());
                try { check(sqlState.product("GPU-4060").salePrice==1234567 && sqlState.product("GPU-4060").spec("vram").equals("test-sql"),
                        "catalogue values come from relational rows rather than stale snapshot"); }
                finally { sqlState.closeRepository(); }
                TechZoneStore sqlOrder=SqliteStore.decode(reopened.read().bytes());
                try { check(sqlOrder.order("ORD-1042").shippingAddress.equals("Địa chỉ từ SQLite"), "order address comes from relational row");
                    check(sqlOrder.rmas().stream().anyMatch(r -> r.problem.equals("Mô tả từ SQLite")),"RMA detail comes from relational row");
                    check(sqlOrder.order("ORD-1042").paymentReference.equals("SQL-TOKEN") && sqlOrder.order("ORD-1038").trackingCode.equals("SQL-TRACKING"),
                            "payment and shipment details come from relational rows");
                    check(sqlOrder.authenticateCustomer("api@example.com","Password123!".toCharArray()).success()
                            && sqlOrder.signedInName().equals("Tên từ SQLite") && sqlOrder.cart().get("RAM-D5-32").quantity==2,
                            "account and cart values come from relational rows"); }
                finally { sqlOrder.closeRepository(); }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement s=c.createStatement()) {
                    s.executeUpdate("DELETE FROM unit_allocations WHERE serial='SN-TZ-PHONES24-0001'");
                }
                boolean allocationMissing=false;
                try { reopened.read(); } catch(IOException ex) { allocationMissing=true; }
                check(allocationMissing,"missing unit allocation must not be silently accepted");
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement s=c.createStatement()) {
                    s.executeUpdate("INSERT INTO unit_allocations(serial,order_id,product_id) VALUES('SN-TZ-PHONES24-0001','ORD-0897','PHONE-S24')");
                }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath()); Statement s=c.createStatement()) {
                    s.executeUpdate("DELETE FROM order_lines WHERE order_id='ORD-1042'");
                    s.executeUpdate("DELETE FROM sales_orders WHERE id='ORD-1042'");
                }
                boolean missingDetected=false;
                try { reopened.read(); } catch(IOException ex) { missingDetected=true; }
                check(missingDetected,"missing relational order must not be silently discarded");
            }
            privacyRevocation();
            upgradeLegacySqlite();
            backendVersionCompatibility();
            standaloneMigration();
            System.out.println("SQLite backend: separate process, migration, HTTP, relational rows, restart and conflict checks passed");
        } finally {
            seed.closeRepository();
            if(Files.exists(images)) Files.delete(images);
            Files.deleteIfExists(path.resolveSibling("test.sqlite-journal"));
            Files.deleteIfExists(path.resolveSibling("test.sqlite-wal"));
            Files.deleteIfExists(path.resolveSibling("test.sqlite-shm"));
            Files.deleteIfExists(path); Files.deleteIfExists(dir);
        }
    }

    private static void backendVersionCompatibility() throws Exception {
        Path session=Files.createTempFile(Path.of("target"),"backend-version-",".properties");
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/products",e -> { e.sendResponseHeaders(200,-1); e.close(); }); server.start();
        try {
            Properties p=new Properties(); p.setProperty("url","http://127.0.0.1:"+server.getAddress().getPort()); p.setProperty("token","x".repeat(40));
            try(OutputStream out=Files.newOutputStream(session)) { p.store(out,""); }
            var method=BackendConnection.class.getDeclaredMethod("existing",Path.class,Process.class); method.setAccessible(true);
            boolean rejected=false;
            try { method.invoke(null,session,null); }
            catch(java.lang.reflect.InvocationTargetException ex) { rejected=ex.getCause() instanceof IOException && ex.getCause().getMessage().contains("phiên bản cũ"); }
            check(rejected,"test client must not reuse an incompatible backend version");
            p.setProperty("protocol",BackendMain.PROTOCOL_VERSION);
            try(OutputStream out=Files.newOutputStream(session)) { p.store(out,""); }
            check(method.invoke(null,session,null)!=null,"test client accepts compatible backend version");
        } finally { server.stop(0); Files.deleteIfExists(session); }
    }

    private static void privacyRevocation() throws Exception {
        Path dir=Files.createTempDirectory(Path.of("target"),"sqlite-privacy-test-").toAbsolutePath();
        Path file=dir.resolve("privacy.sqlite"); TechZoneStore store=new TechZoneStore();
        try {
            store.setInteractionConsent(true); store.recordProductView("GPU-4060");
            store.recordRecommendationResult("CPU-14600K",store.recommend("CPU-14600K",java.util.Set.of(),3));
            check(!store.recommendationImpressions().isEmpty(),"impression fixture exists before consent withdrawal");
            try(SqliteStore db=new SqliteStore(file,store)) {
                store.setInteractionConsent(false); db.save(store,db.read().revision());
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file); Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT COUNT(*) FROM interaction_events")) {
                    check(r.next() && r.getInt(1)==0,"revoking consent removes persisted relational interactions");
                }
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file); Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT COUNT(*) FROM recommendation_impressions")) {
                    check(r.next() && r.getInt(1)==0,"revoking consent removes persisted recommendation impressions");
                }
                TechZoneStore loaded=SqliteStore.decode(db.read().bytes());
                try { check(loaded.interactionEvents().isEmpty(),"revoked interactions stay removed after reload"); }
                finally { loaded.closeRepository(); }
            }
        } finally {
            store.closeRepository();
            Path allowed=Path.of("target").toAbsolutePath().normalize();
            if(!dir.normalize().startsWith(allowed)) throw new IOException("Unexpected test directory");
            try(var paths=Files.walk(dir)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void upgradeLegacySqlite() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"sqlite-upgrade-test-").toAbsolutePath();
        Path old=root.resolve("old.sqlite"); TechZoneStore seed=new TechZoneStore();
        seed.setThreshold("GPU-4060",19,"test");
        seed.createCurrentCustomerRma("SN-TZ-PHONES24-0001","Lỗi trước nâng cấp");
        seed.setInteractionConsent(true); seed.recordProductView("GPU-4060");
        seed.registerCustomer("Khách cũ","old@example.com","Password123!".toCharArray());
        seed.authenticateCustomer("old@example.com","Password123!".toCharArray()); seed.addToCart("RAM-D5-32",1);
        try {
            Class.forName("org.sqlite.JDBC");
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+old); Statement s=c.createStatement()) {
                s.execute("CREATE TABLE app_state(id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, snapshot BLOB NOT NULL)");
                s.execute("CREATE TABLE products(id TEXT PRIMARY KEY, sku TEXT NOT NULL UNIQUE, name TEXT NOT NULL, category TEXT NOT NULL, brand TEXT NOT NULL, price INTEGER NOT NULL, sale_price INTEGER NOT NULL, active INTEGER NOT NULL)");
                s.execute("CREATE TABLE product_specs(product_id TEXT NOT NULL REFERENCES products(id), spec_key TEXT NOT NULL, spec_value TEXT NOT NULL, PRIMARY KEY(product_id,spec_key))");
                s.execute("CREATE TABLE inventory(product_id TEXT PRIMARY KEY REFERENCES products(id), available INTEGER NOT NULL, reserved INTEGER NOT NULL, allocated INTEGER NOT NULL, threshold INTEGER NOT NULL)");
                s.execute("CREATE TABLE sales_orders(id TEXT PRIMARY KEY, number TEXT NOT NULL, customer_id TEXT NOT NULL, status TEXT NOT NULL, total INTEGER NOT NULL, created_at TEXT NOT NULL)");
                s.execute("CREATE TABLE order_lines(order_id TEXT NOT NULL REFERENCES sales_orders(id), product_id TEXT NOT NULL REFERENCES products(id), quantity INTEGER NOT NULL, unit_price INTEGER NOT NULL, PRIMARY KEY(order_id,product_id))");
                s.execute("CREATE TABLE inventory_units(serial TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), status TEXT NOT NULL, order_id TEXT)");
                try(PreparedStatement q=c.prepareStatement("INSERT INTO app_state VALUES(1,7,?)")) { q.setBytes(1,SqliteStore.encode(seed)); q.executeUpdate(); }
            }
            try(SqliteStore upgraded=new SqliteStore(old,new TechZoneStore())) {
                check(upgraded.read().revision()==7,"migration preserves revision");
                TechZoneStore loaded=SqliteStore.decode(upgraded.read().bytes());
                try { check(loaded.balance("GPU-4060").reorderThreshold==19 && loaded.order("ORD-1042")!=null
                        && loaded.rmas().stream().anyMatch(r -> r.problem.equals("Lỗi trước nâng cấp"))
                        && loaded.interactionEvents().stream().anyMatch(e -> e.type.equals("product_view"))
                        && loaded.authenticateCustomer("old@example.com","Password123!".toCharArray()).success()
                        && loaded.cart().containsKey("RAM-D5-32"),"migration keeps legacy business data and customer cart"); }
                finally { loaded.closeRepository(); }
            }
            try(var files=Files.list(root)) {
                Path backup=files.filter(p->p.getFileName().toString().contains(".pre-core-")).findFirst().orElseThrow();
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+backup); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT revision FROM app_state WHERE id=1")) {
                    check(r.next() && r.getLong(1)==7,"migration creates a readable pre-upgrade backup");
                }
                Path corrupt=root.resolve("corrupt.sqlite"); Files.copy(backup,corrupt);
                byte[] invalid=new byte[]{1,2,3};
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+corrupt); PreparedStatement q=c.prepareStatement("UPDATE app_state SET snapshot=? WHERE id=1")) {
                    q.setBytes(1,invalid); q.executeUpdate();
                }
                boolean rejected=false;
                try(SqliteStore ignored=new SqliteStore(corrupt,new TechZoneStore())) { }
                catch(IOException ex) { rejected=true; }
                check(rejected,"migration rejects an invalid legacy snapshot");
                try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+corrupt); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT revision,snapshot FROM app_state")) {
                    check(r.next() && r.getLong(1)==7 && Arrays.equals(r.getBytes(2),invalid),"failed migration preserves original snapshot and revision");
                }
            }
        } finally {
            seed.closeRepository();
            Path allowed=Path.of("target").toAbsolutePath().normalize();
            if(!root.normalize().startsWith(allowed)) throw new IOException("Unexpected test directory");
            try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void standaloneMigration() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"backend-process-test-").toAbsolutePath();
        Path data=Files.createDirectories(root.resolve("data")), legacy=data.resolve("techzone-demo.dat");
        TechZoneStore initial=new TechZoneStore();
        initial.setThreshold("GPU-4060", 17, "test");
        try(DemoStoreRepository old=DemoStoreRepository.open(legacy)) { old.save(initial); }
        initial.closeRepository(); byte[] before=Files.readAllBytes(legacy);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=Path.of("target","classes").toAbsolutePath()+File.pathSeparator+Path.of("lib").toAbsolutePath()+File.separator+"*";
        Process process=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",cp,"vn.techzone.backend.BackendMain")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("backend.log").toFile()).start();
        try {
            Path session=data.resolve("backend-session.properties");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!Files.isRegularFile(session) && process.isAlive() && System.nanoTime()<end) Thread.sleep(100);
            check(Files.isRegularFile(session),"standalone backend must start: "+Files.readString(root.resolve("backend.log")));
            Properties props=new Properties(); try(InputStream in=Files.newInputStream(session)) { props.load(in); }
            RemoteStoreRepository remote=new RemoteStoreRepository(URI.create(props.getProperty("url")),props.getProperty("token"));
            TechZoneStore loaded=remote.loadOrCreate();
            try { check(loaded.balance("GPU-4060").reorderThreshold==17,"imports legacy data into SQLite"); }
            finally { loaded.closeRepository(); }
            check(Files.isRegularFile(data.resolve("techzone.sqlite")),"standalone creates SQLite database");
            check(Arrays.equals(before,Files.readAllBytes(legacy)),"migration must not change old file");
            process.destroy(); check(process.waitFor(10,TimeUnit.SECONDS),"standalone backend must stop");
            String probeCp=cp+File.pathSeparator+Path.of("target","test-classes").toAbsolutePath();
            Process probe=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",probeCp,StartupProbe.class.getName())
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve("probe.log").toFile()).start();
            check(probe.waitFor(75,TimeUnit.SECONDS),"test client automatic startup timed out");
            check(probe.exitValue()==0,"test client automatic startup: "+Files.readString(root.resolve("probe.log")));
            try(SqliteStore db=new SqliteStore(data.resolve("techzone.sqlite"),new TechZoneStore())) {
                TechZoneStore after=SqliteStore.decode(db.read().bytes());
                try { check(after.balance("GPU-4060").reorderThreshold==18,"test client save reaches backend SQLite"); }
                finally { after.closeRepository(); }
            }
        } finally {
            process.destroy(); if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
            Path allowed=Path.of("target").toAbsolutePath().normalize();
            if(!root.normalize().startsWith(allowed)) throw new IOException("Unexpected test directory");
            try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
