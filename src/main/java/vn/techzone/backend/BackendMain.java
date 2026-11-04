package vn.techzone.backend;

import vn.techzone.application.TechZoneStore;
import vn.techzone.infrastructure.DemoStoreRepository;
import java.nio.channels.*;
import java.nio.file.*;
import java.io.*;
import java.util.Properties;

/** Standalone localhost backend serving the web demo and recording its local session. */
public final class BackendMain {
    public static final String PROTOCOL_VERSION = "3";
    private BackendMain() { }
    public static void main(String[] args) throws Exception {
        Path data=Path.of("data").toAbsolutePath(); Files.createDirectories(data);
        Path session=data.resolve("backend-session.properties"), lockPath=data.resolve("backend.lock");
        FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock lock=channel.tryLock();
        if(lock==null) throw new IOException("Backend TechZone đã chạy trong thư mục dữ liệu này.");
        SqliteStore database=null; CatalogBackend backend=null;
        try {
            Path dbPath=data.resolve("techzone.sqlite"), legacy=data.resolve("techzone-demo.dat");
            TechZoneStore initial;
            if (!SqliteStore.hasState(dbPath) && Files.isRegularFile(legacy)) {
                try(DemoStoreRepository old=DemoStoreRepository.open(legacy)) { initial=old.loadOrCreate(); }
            } else initial=new TechZoneStore();
            database=new SqliteStore(dbPath,initial);
            database.importRealCatalog();
            database.importVerifiedManufacturerPair();
            database.importVerifiedRyzen7600Pairs();
            database.importVerifiedStorageFacts();
            TechZoneStore current=SqliteStore.decode(database.read().bytes()); initial.closeRepository();
            backend=new CatalogBackend(current,data.resolve("product-images")); backend.attachDatabase(database);
            backend.attachSemanticSearch(new SemanticSearch());
            Properties props=new Properties(); props.setProperty("url",backend.baseUri().toString()); props.setProperty("token",backend.token());
            props.setProperty("protocol",PROTOCOL_VERSION);
            Path temp=Files.createTempFile(data,".backend-session-",".tmp");
            try(OutputStream out=Files.newOutputStream(temp)) { props.store(out,"TechZone localhost session"); }
            Files.move(temp,session,StandardCopyOption.REPLACE_EXISTING);
            SqliteStore finalDb=database; CatalogBackend finalBackend=backend; String myToken=backend.token();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                finalBackend.close(); try { finalDb.close(); } catch(Exception ignored) { }
                try {
                    Properties currentProps=new Properties();
                    if(Files.exists(session)) { try(InputStream in=Files.newInputStream(session)) { currentProps.load(in); }
                        if(myToken.equals(currentProps.getProperty("token"))) Files.deleteIfExists(session); }
                } catch(Exception ignored) { }
                try { lock.release(); channel.close(); } catch(Exception ignored) { }
            },"techzone-backend-shutdown"));
            System.out.println("TechZone backend: " + backend.baseUri() + " · SQLite " + dbPath);
            if (java.util.Arrays.asList(args).contains("--web")) {
                System.out.println("Mở trình duyệt tại: " + backend.baseUri());
                if (java.awt.Desktop.isDesktopSupported()) try { java.awt.Desktop.getDesktop().browse(backend.baseUri()); }
                catch (Exception ex) { System.out.println("Hãy mở địa chỉ trên trong trình duyệt."); }
            }
            Thread.currentThread().join();
        } catch(Exception ex) {
            if(backend!=null) backend.close(); if(database!=null) database.close();
            lock.release(); channel.close(); throw ex;
        }
    }
}
