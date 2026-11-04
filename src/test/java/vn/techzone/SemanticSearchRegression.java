package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SemanticSearch;
import vn.techzone.backend.CatalogBackend;
import vn.techzone.backend.CatalogClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Exercises the local pretrained model and checks that hard filters remain authoritative. */
public final class SemanticSearchRegression {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if(!Files.isRegularFile(Path.of(".venv-ai","Scripts","python.exe")) || (!Files.isRegularFile(Path.of("models","semantic-mini","model.safetensors")) && !Files.isRegularFile(Path.of("models","bge-m3",".techzone-ready")))) {
            System.out.println("Semantic search test skipped: run setup-ai.bat to install the model"); return;
        }
        TechZoneStore store=new TechZoneStore();
        try(SemanticSearch semantic=new SemanticSearch()) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(190);
            while(!semantic.ready() && System.nanoTime()<until) Thread.sleep(250);
            check(semantic.ready(),"pretrained model did not start; inspect data/semantic-search.log");
            if(Files.isRegularFile(Path.of("models","bge-m3",".techzone-ready")))
                check("BGE-M3".equals(semantic.modelName()),"installed BGE-M3 must be selected instead of the fallback");
            List<SemanticSearch.Document> documents=store.products().stream().map(p -> new SemanticSearch.Document(p.id,
                    p.name+" "+p.sku+" "+p.category+" "+p.brand+" "+p.description+" "+p.specs)).toList();
            Map<String,Double> scores=null;
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(200);
            while(scores==null && System.nanoTime()<until) { scores=semantic.rank("bo mach chu LGA1700 DDR5",documents); if(scores==null) Thread.sleep(250); }
            check(scores!=null && scores.size()==documents.size(),"model did not return a score for each product");
            var found=store.searchWithEmbeddings("LGA1700 DDR5","Mainboard","LGA1700","DDR5",0,Map.of(),scores);
            check(found.stream().anyMatch(p -> p.id.equals("MB-B760-D5")) && found.stream().noneMatch(p -> p.id.equals("MB-B760-D4")),
                    "pretrained ranking must honor DDR/socket filters");
            check(store.searchWithEmbeddings("DDR4","Mainboard","LGA1700","DDR5",0,Map.of(),scores).isEmpty(),
                    "pretrained scores must not override contradictory constraints");
            Path images=Files.createTempDirectory(Path.of("target"),"semantic-test-");
            try(CatalogBackend api=new CatalogBackend(store,images,false)) {
                api.attachSemanticSearch(semantic); CatalogClient client=new CatalogClient(api.baseUri(),api.token());
                check(client.aiStatusJson().contains(semantic.modelName()),"status API must report the ready model");
                String[][] fixtures={{"bo mạch chủ LGA1700 DDR5","Mainboard","LGA1700","DDR5","MB-B760-D5"},
                        {"ổ cứng lưu trữ tốc độ cao","SSD","","","SSD-990"},
                        {"nguồn 750 watt","PSU","","","PSU-750"},
                        {"điện thoại Samsung Galaxy S24","Điện thoại","","","PHONE-S24"}};
                int hits=0; double ndcg=0; List<Double> times=new ArrayList<>();
                for(String[] fixture:fixtures) {
                    long start=System.nanoTime();
                    var result=client.search(fixture[0],fixture[1],fixture[2],fixture[3],0,true,Map.of());
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(200);
                    while (!result.strategy().contains(semantic.modelName()) && System.nanoTime()<deadline) {
                        Thread.sleep(250);
                        result=client.search(fixture[0],fixture[1],fixture[2],fixture[3],0,true,Map.of());
                    }
                    times.add((System.nanoTime()-start)/1_000_000.0);
                    check(result.strategy().contains(semantic.modelName()),"backend did not use pretrained model");
                    int position=result.ids().indexOf(fixture[4]);
                    if(position>=0 && position<3) { hits++; ndcg+=1.0/(Math.log(position+2)/Math.log(2)); }
                }
                store.setInteractionConsent(true);
                store.recordProductView("GPU-4060");
                String recommendations=client.productRecommendationsJson("CPU-14600K");
                check(recommendations.contains("AI gợi ý") || recommendations.contains("Fallback"),"recommendation API must report its ranking or fallback strategy");
                check(!recommendations.contains("MB-B650"),"pretrained recommendations must not override compatibility");
                store.setSearchServiceAvailable(false,"test");
                var fallback=client.search("LGA1700 DDR5","Mainboard","LGA1700","DDR5",0,true,Map.of());
                check(fallback.strategy().startsWith("Fallback") && fallback.ids().contains("MB-B760-D5") && !fallback.ids().contains("MB-B760-D4"),
                        "search fallback must keep hard filters");
                String testedModel=semantic.modelName();
                var processField=SemanticSearch.class.getDeclaredField("process"); processField.setAccessible(true);
                Process child=(Process)processField.get(semantic); child.destroyForcibly();
                check(child.waitFor(10,TimeUnit.SECONDS),"model worker must stop during failure test");
                check(!semantic.ready() && semantic.status().contains("chờ thử lại"),"status must not show a dead worker as ready");
                times.sort(Double::compareTo);
                System.out.printf(java.util.Locale.ROOT,"Semantic search (%s): backend, hard filters, fallback passed; mock Recall@3 %d/%d, NDCG@3 %.3f, max %.1f ms%n",
                        testedModel,hits,fixtures.length,ndcg/fixtures.length,times.get(times.size()-1));
            } finally { Files.deleteIfExists(images); }
        } finally { store.closeRepository(); }
    }
}
