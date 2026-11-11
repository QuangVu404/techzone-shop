package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;

/** Real Qwen + BGE through the local HTTP API, in an isolated database. */
public final class AdvisorApiLiveRegression {
    private static HttpResponse<String> get(HttpClient client, URI base,String token,String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).header("X-TechZone-Token",token)
                .timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        if(!Files.isRegularFile(Path.of("models/qwen-chat/.techzone-ready")) && !Files.isRegularFile(Path.of("models/qwen3-chat/.techzone-ready"))) { System.out.println("Live chat test skipped"); return; }
        Path root=Files.createTempDirectory(Path.of("target"),"advisor-live-");
        Path path=root.resolve("test.sqlite"), images=Files.createDirectory(root.resolve("images"));
        TechZoneStore seed=new TechZoneStore();
        try (SqliteStore db=new SqliteStore(path,seed);
             CatalogBackend api=new CatalogBackend(SqliteStore.decode(db.read().bytes()),images,false)) {
            api.attachDatabase(db); api.attachSemanticSearch(new SemanticSearch());
            HttpClient client=HttpClient.newHttpClient(); long deadline=System.currentTimeMillis()+180000;
            while(System.currentTimeMillis()<deadline && !get(client,api.baseUri(),api.token(),"/v1/web/advisor-status").body().contains("Qwen")) Thread.sleep(500);
            String status=get(client,api.baseUri(),api.token(),"/v1/web/advisor-status").body();
            if(!status.contains("Qwen")) throw new AssertionError("Model not ready: "+status);
            String message="Tôi muốn cấu hình PC chơi game 35 triệu";
            String body="message="+URLEncoder.encode(message,StandardCharsets.UTF_8)+"&context=&conversation=";
            var request=HttpRequest.newBuilder(api.baseUri().resolve("/v1/web/advisor"))
                    .header("X-TechZone-Token",api.token()).header("Content-Type","application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(220)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200 || !response.body().contains("Qwen") || !response.body().contains("CPU-"))
                throw new AssertionError("Live Qwen + catalogue response failed: "+response.body());
            System.out.println("Live adviser API: real Qwen response and grounded catalogue configuration passed");
            System.out.println(response.body().substring(0,Math.min(response.body().length(),900)));
            String natural="Mình học kiến trúc dùng revit, có 35 củ cần ráp dàn máy";
            String followup="message="+URLEncoder.encode(natural,StandardCharsets.UTF_8)+"&context=&conversation=";
            var followRequest=HttpRequest.newBuilder(api.baseUri().resolve("/v1/web/advisor"))
                    .header("X-TechZone-Token",api.token()).header("Content-Type","application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(220)).POST(HttpRequest.BodyPublishers.ofString(followup)).build();
            var followResponse=client.send(followRequest,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(followResponse.statusCode()!=200 || !followResponse.body().contains("\"configuration\":true")
                    || !followResponse.body().contains("BGE-M3") || !followResponse.body().contains("\"rankingMethod\":\"semantic\"")
                    || !followResponse.body().contains("\"semanticScores\":{") )
                throw new AssertionError("BGE did not interpret the natural request: "+followResponse.body());
            System.out.println("Live adviser API: BGE interpreted an indirect Vietnamese build request");
        } finally {
            seed.closeRepository();Files.deleteIfExists(path);Files.deleteIfExists(images);Files.deleteIfExists(root);
        }
    }
}
