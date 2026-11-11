package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.CatalogBackend;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Checks the same-category API for all bundled products with the AI service offline. */
public final class SimilarProductsRegression {
    public static void main(String[] args) throws Exception {
        List<TechZoneStore.GearVNCatalogProduct> listings=new ArrayList<>();
        for(String line:Files.readAllLines(Path.of("src/main/resources/vn/techzone/catalog/gearvn-catalog.tsv"))) {
            String[] raw=line.split("\\t",-1), f=new String[raw.length];
            for(int i=0;i<f.length;i++)f[i]=new String(Base64.getDecoder().decode(raw[i]),StandardCharsets.UTF_8);
            Map<String,String> specs=new LinkedHashMap<>();
            for(int i=12;i<f.length;i+=2)specs.put(f[i],f[i+1]);
            listings.add(new TechZoneStore.GearVNCatalogProduct(f[0],f[1],f[2],f[3],f[4],Long.parseLong(f[5]),Long.parseLong(f[6]),f[7],f[8],f[9],f[10],null,specs));
        }
        if(listings.size()!=200)throw new AssertionError("Expected 200 products");
        TechZoneStore store=new TechZoneStore();
        Path images=Files.createTempDirectory(Path.of("target"),"similar-offline-");
        try {
            store.syncGearVNCatalog(listings);
            try(CatalogBackend api=new CatalogBackend(store,images,false)) {
                HttpClient client=HttpClient.newHttpClient();
                for(var listing:listings) {
                    var request=HttpRequest.newBuilder(api.baseUri().resolve("/v1/products/"+listing.id()+"/similar"))
                            .header("X-TechZone-Token",api.token()).GET().build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    String body=response.body();
                    List<TechZoneStore.GearVNCatalogProduct> expected=listings.stream()
                            .filter(p->!p.id().equals(listing.id()) && p.category().equals(listing.category())).toList();
                    if(response.statusCode()!=200 || !body.contains("\"ready\":false") ||
                            !body.contains("\"total\":"+expected.size()+",") || expected.isEmpty())
                        throw new AssertionError("Incomplete fallback for "+listing.id()+": "+body);
                    for(var p:expected)if(!body.contains("\"id\":\""+p.id()+"\""))
                        throw new AssertionError("Missing same-category product "+p.id());
                    if(body.contains("\"id\":\""+listing.id()+"\""))throw new AssertionError("Primary returned as alternative");
                }
                System.out.println("Similar products: 200/200 products return all same-category alternatives without AI");
            }
        } finally { store.closeRepository(); Files.deleteIfExists(images); }
    }
}
