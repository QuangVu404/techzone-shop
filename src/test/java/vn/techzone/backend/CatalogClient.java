package vn.techzone.backend;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.net.URLEncoder;
import java.util.*;

/** HTTP client for the local authenticated catalogue API. */
public final class CatalogClient {
    private final URI base;
    private final String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public CatalogClient(URI base, String token) { this.base=base; this.token=token; }
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(190)).header("X-TechZone-Token",token); }
    private static String safeId(String id) { if(id==null || !id.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Mã sản phẩm không hợp lệ."); return id; }
    private static byte[] imageBytes(Path path) throws IOException { if(path==null || !Files.isRegularFile(path) || Files.size(path)>30L*1024*1024)throw new IOException("Ảnh không hợp lệ hoặc lớn hơn 30 MB."); return Files.readAllBytes(path); }
    private static void requireOk(HttpResponse<byte[]> response) throws IOException { if(response.statusCode()!=200)throw new IOException(new String(response.body(),StandardCharsets.UTF_8)); }
    public String productsJson() throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public String aiStatusJson() throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/web/ai-status").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public String inventoryJson() throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/inventory").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public String ordersJson() throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/orders").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public record SearchResult(List<String> ids, String strategy) { }
    private static String form(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    public SearchResult search(String query,String category,String socket,String memory,long maxPrice,boolean semantic,Map<String,String> specs)
            throws IOException,InterruptedException {
        StringBuilder path=new StringBuilder("/v1/search?query=").append(form(query)).append("&category=").append(form(category))
                .append("&socket=").append(form(socket)).append("&memory=").append(form(memory))
                .append("&maxPrice=").append(maxPrice).append("&semantic=").append(semantic);
        for(var entry:specs.entrySet()) path.append("&").append(form("spec."+entry.getKey())).append("=").append(form(entry.getValue()));
        HttpRequest req=HttpRequest.newBuilder(base.resolve(path.toString())).timeout(Duration.ofSeconds(3))
                .header("X-TechZone-Token",token).GET().build();
        HttpResponse<byte[]> response=http.send(req,HttpResponse.BodyHandlers.ofByteArray()); requireOk(response);
        String encoded=response.headers().firstValue("X-TechZone-Strategy").orElse("");
        try {
            String strategy=new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8);
            List<String> ids=new String(response.body(),StandardCharsets.UTF_8).lines().filter(id -> !id.isBlank()).map(CatalogClient::safeId).toList();
            return new SearchResult(ids,strategy);
        } catch(IllegalArgumentException ex) { throw new IOException("Phản hồi tìm kiếm không hợp lệ.",ex); }
    }
    public String productJson(String id) throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products/"+safeId(id)).GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public String productDetails(String id) throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products/"+safeId(id)+"/details").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public String productRecommendationsJson(String id) throws IOException, InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products/"+safeId(id)+"/recommendations").GET().build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r); return new String(r.body(),StandardCharsets.UTF_8);
    }
    public void setImage(String id,Path file) throws IOException,InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products/"+safeId(id)+"/image").header("Content-Type","application/octet-stream").PUT(HttpRequest.BodyPublishers.ofByteArray(imageBytes(file))).build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r);
    }
    public BufferedImage image(String id) throws IOException,InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/products/"+safeId(id)+"/image").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        if(r.statusCode()==404)return null; requireOk(r); return ImageIO.read(new ByteArrayInputStream(r.body()));
    }
    public List<String> searchImage(Path file) throws IOException,InterruptedException {
        HttpResponse<byte[]> r=http.send(request("/v1/search/images").header("Content-Type","application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes(file))).build(),HttpResponse.BodyHandlers.ofByteArray()); requireOk(r);
        return new String(r.body(),StandardCharsets.UTF_8).lines().filter(line->!line.isBlank()).map(line->line.split("\\t",2)[0]).toList();
    }
}
