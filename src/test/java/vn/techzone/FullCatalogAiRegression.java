package vn.techzone;

import vn.techzone.backend.SemanticSearch;
import vn.techzone.backend.CatalogBackend;
import vn.techzone.application.TechZoneStore;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Checks real-model coverage over the bundled catalogue, without claiming relevance quality. */
public final class FullCatalogAiRegression {
    public static void main(String[] args) throws Exception {
        if(!Files.isRegularFile(Path.of(".venv-ai/Scripts/python.exe")) ||
                !Files.isRegularFile(Path.of("models/bge-m3/.techzone-ready"))) {
            System.out.println("Full catalogue AI test skipped: BGE-M3 not installed"); return;
        }
        List<SemanticSearch.Document> docs=new ArrayList<>();
        List<TechZoneStore.GearVNCatalogProduct> listings=new ArrayList<>();
        for(String line:Files.readAllLines(Path.of("src/main/resources/vn/techzone/catalog/gearvn-catalog.tsv"))) {
            String[] fields=line.split("\\t",-1);
            String[] decoded=new String[fields.length];
            for(int i=0;i<fields.length;i++) decoded[i]=new String(Base64.getDecoder().decode(fields[i]),StandardCharsets.UTF_8);
            docs.add(new SemanticSearch.Document(decoded[0],decoded[1]+" "+decoded[2]+" "+decoded[3]+" "+decoded[4]));
            Map<String,String> specs=new LinkedHashMap<>();
            for(int i=12;i<decoded.length;i+=2) specs.put(decoded[i],decoded[i+1]);
            listings.add(new TechZoneStore.GearVNCatalogProduct(decoded[0],decoded[1],decoded[2],decoded[3],decoded[4],
                    Long.parseLong(decoded[5]),Long.parseLong(decoded[6]),decoded[7],decoded[8],decoded[9],decoded[10],null,specs));
        }
        if(docs.size()!=200 || docs.stream().map(SemanticSearch.Document::id).distinct().count()!=200)
            throw new AssertionError("Expected 200 distinct catalogue products");
        try(SemanticSearch engine=new SemanticSearch()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(190);
            while(!engine.ready() && System.nanoTime()<deadline) Thread.sleep(250);
            if(!engine.ready()) throw new AssertionError("BGE-M3 did not start");
            Map<String,Double> scores=engine.rank("linh kiện máy tính chơi game",docs,180000);
            if(scores==null || scores.size()!=200 || docs.stream().anyMatch(d->!scores.containsKey(d.id())))
                throw new AssertionError("Model did not score all 200 products");
            System.out.println("Full catalogue AI: BGE-M3 scored all 200 distinct products");
            TechZoneStore store=new TechZoneStore();
            Path images=Files.createTempDirectory(Path.of("target"),"similar-ai-");
            try {
                store.syncGearVNCatalog(listings);
                for(var item:listings) store.balance(item.id()).available=1;
                try(CatalogBackend api=new CatalogBackend(store,images,false)) {
                    api.attachSemanticSearch(engine);
                    HttpClient client=HttpClient.newHttpClient();
                    var request=HttpRequest.newBuilder(api.baseUri().resolve("/v1/products/"+listings.get(0).id()+"/similar"))
                            .header("X-TechZone-Token",api.token()).GET().build();
                    String body=""; long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
                    while(System.nanoTime()<until) {
                        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        if(response.statusCode()!=200) throw new AssertionError("Similar-product API failed: "+response.statusCode());
                        body=response.body();
                        if(body.contains("\"ready\":true")) break;
                        Thread.sleep(300);
                    }
                    if(!body.contains("\"ready\":true") || body.contains("\"items\":[]"))
                        throw new AssertionError("Model did not return similar products: "+body);
                    System.out.println("Similar-product API: model-ranked alternatives returned");
                    var candidateRequest=HttpRequest.newBuilder(api.baseUri().resolve("/v1/products/"+listings.get(0).id()+"/compatibility-candidates"))
                            .header("X-TechZone-Token",api.token()).GET().build();
                    body=""; until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
                    while(System.nanoTime()<until) {
                        var response=client.send(candidateRequest,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        if(response.statusCode()!=200) throw new AssertionError("Compatibility candidate API failed: "+response.statusCode());
                        body=response.body();
                        if(body.contains("\"ranked\":true")) break;
                        Thread.sleep(300);
                    }
                    if(!body.contains("\"ranked\":true") || body.contains("\"items\":[]") || !body.contains("\"status\":"))
                        throw new AssertionError("Model did not rank candidate components: "+body);
                    System.out.println("Compatibility candidates API: real model ranking and evidence status returned");
                }
            } finally { store.closeRepository(); Files.deleteIfExists(images); }
        }
    }
}
