package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.Map;
import vn.techzone.domain.TechZoneModels.PaymentVerdict;

/** Client-side session uses backend storage with optimistic revision checking. */
public final class RemoteStoreRepository implements StoreRepository {
    private final URI base;
    private final String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long revision=-1;
    public RemoteStoreRepository(URI base,String token) { this.base=base; this.token=token; }
    private HttpRequest.Builder request() { return HttpRequest.newBuilder(base.resolve("/v1/session/state")).timeout(Duration.ofSeconds(30)).header("X-TechZone-Token",token); }
    @Override public TechZoneStore loadOrCreate() throws IOException {
        try {
            var response=http.send(request().GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IOException("Backend từ chối đọc dữ liệu: "+response.statusCode());
            revision=Long.parseLong(response.headers().firstValue("X-TechZone-Revision").orElseThrow());
            return SqliteStore.decode(response.body());
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Đọc dữ liệu bị gián đoạn.",ex); }
        catch(RuntimeException ex) { throw new IOException("Phản hồi backend không hợp lệ.",ex); }
    }
    @Override public void save(TechZoneStore store) throws IOException {
        if(revision<0) throw new IOException("Chưa tải dữ liệu từ backend.");
        try {
            var response=http.send(request().header("X-TechZone-Revision",Long.toString(revision)).header("Content-Type","application/octet-stream")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(SqliteStore.encode(store))).build(),HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IOException("Backend không lưu được: "+new String(response.body(),StandardCharsets.UTF_8));
            revision=Long.parseLong(response.headers().firstValue("X-TechZone-Revision").orElseThrow());
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Lưu dữ liệu bị gián đoạn.",ex); }
        catch(RuntimeException ex) { throw new IOException("Phản hồi backend không hợp lệ.",ex); }
    }
    public record CheckoutResponse(TechZoneStore store, String message, String orderId, boolean confirmed) { }
    public record OperationResponse(TechZoneStore store,String message) { }
    private static String form(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
    /** Checkout executes against the persisted backend state, never against a stale local copy. */
    public CheckoutResponse checkout(String address,String payment,String promotion,PaymentVerdict verdict,String idempotency) throws IOException {
        if(revision<0) throw new IOException("Chưa tải dữ liệu từ backend.");
        String body="address="+form(address)+"&payment="+form(payment)+"&promotion="+form(promotion)
                +"&verdict="+form(verdict.name())+"&idempotency="+form(idempotency);
        try {
            HttpRequest req=HttpRequest.newBuilder(base.resolve("/v1/checkout")).timeout(Duration.ofSeconds(30))
                    .header("X-TechZone-Token",token).header("X-TechZone-Revision",Long.toString(revision))
                    .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IOException("Backend không xác nhận checkout: "+new String(response.body(),StandardCharsets.UTF_8));
            long next=Long.parseLong(response.headers().firstValue("X-TechZone-Revision").orElseThrow());
            String encoded=response.headers().firstValue("X-TechZone-Message").orElseThrow();
            String message=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
            String orderId=response.headers().firstValue("X-TechZone-Order").orElse("");
            boolean confirmed=Boolean.parseBoolean(response.headers().firstValue("X-TechZone-Confirmed").orElse("false"));
            TechZoneStore updated=SqliteStore.decodeSession(response.body());
            revision=next;
            return new CheckoutResponse(updated,message,orderId,confirmed);
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Yêu cầu bị gián đoạn; hãy kiểm tra đơn trước khi thử lại.",ex); }
        catch(RuntimeException ex) { throw new IOException("Phản hồi checkout không hợp lệ; hãy kiểm tra đơn trước khi thử lại.",ex); }
    }
    public OperationResponse operation(Map<String,String> fields) throws IOException {
        if(revision<0) throw new IOException("Chưa tải dữ liệu từ backend.");
        StringBuilder body=new StringBuilder();
        for(var entry:fields.entrySet()) {
            if(body.length()>0) body.append('&'); body.append(form(entry.getKey())).append('=').append(form(entry.getValue()));
        }
        try {
            HttpRequest req=HttpRequest.newBuilder(base.resolve("/v1/operations")).timeout(Duration.ofSeconds(30))
                    .header("X-TechZone-Token",token).header("X-TechZone-Revision",Long.toString(revision))
                    .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200) throw new IOException("Backend không xác nhận thao tác: "+new String(response.body(),StandardCharsets.UTF_8));
            long next=Long.parseLong(response.headers().firstValue("X-TechZone-Revision").orElseThrow());
            String encoded=response.headers().firstValue("X-TechZone-Message").orElseThrow();
            String message=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
            TechZoneStore updated=SqliteStore.decodeSession(response.body()); revision=next;
            return new OperationResponse(updated,message);
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Thao tác bị gián đoạn; hãy kiểm tra trạng thái trước khi thử lại.",ex); }
        catch(RuntimeException ex) { throw new IOException("Phản hồi không hợp lệ; hãy kiểm tra trạng thái trước khi thử lại.",ex); }
    }
    @Override public Path dataFile() { return Path.of("data", "techzone.sqlite"); }
    @Override public void close() throws IOException { }
}
