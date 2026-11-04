package vn.techzone.backend;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Properties;

/** Test helper for connecting to or starting a local backend. */
public final class BackendConnection {
    public record Session(URI uri,String token,Process ownedProcess) { }
    private BackendConnection() { }
    private static Session existing(Path path,Process owned) throws IOException {
        Properties p=new Properties(); URI uri; String token; int status;
        try {
            try(InputStream in=Files.newInputStream(path)) { p.load(in); }
            uri=URI.create(p.getProperty("url","")); token=p.getProperty("token","");
            if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 || token.length()<32) return null;
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            var request=HttpRequest.newBuilder(uri.resolve("/v1/products")).timeout(Duration.ofSeconds(2)).header("X-TechZone-Token",token).GET().build();
            status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Kết nối backend bị gián đoạn.",ex); }
        catch(Exception ignored) { return null; }
        if(status!=200) return null;
        if(!BackendMain.PROTOCOL_VERSION.equals(p.getProperty("protocol")))
            throw new IOException("Backend phiên bản cũ đang chạy. Hãy đóng backend cũ rồi khởi động lại TechZone.");
        return new Session(uri,token,owned);
    }
    public static Session connectOrStart() throws IOException {
        Path directory=Path.of("data"); Files.createDirectories(directory);
        Path info=directory.resolve("backend-session.properties");
        Session existing=existing(info,null); if(existing!=null) return existing;
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        if(!Files.isRegularFile(Path.of(java))) java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process process=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),"vn.techzone.backend.BackendMain")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("backend.log").toFile())).start();
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) {
            Session found=existing(info,process.isAlive()?process:null); if(found!=null) return found;
            try { Thread.sleep(250); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); process.destroy(); throw new IOException("Khởi động backend bị gián đoạn.",ex); }
        }
        if(process.isAlive()) process.destroy();
        throw new IOException("Không thể khởi động backend. Xem data/backend.log; kiểm tra JDK, SQLite JDBC và quyền truy cập dữ liệu.");
    }
}

