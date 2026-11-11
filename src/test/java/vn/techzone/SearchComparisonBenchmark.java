package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SemanticSearch;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small, labelled development comparison. Times are in-process, warm-cache and not HTTP latency. */
public final class SearchComparisonBenchmark {
    private record Query(String id,String text,String relevant) { }
    private record Row(String mode,Query query,int rank,double milliseconds,List<String> top3) { }
    private static String clean(String s) { return s.replace('\t',' ').replace('\n',' ').replace('\r',' '); }
    private static double ndcg(int rank) { return rank>0 && rank<=3 ? 1.0/(Math.log(rank+1)/Math.log(2)) : 0; }
    private static double percentile(List<Double> values,double fraction) {
        List<Double> sorted=values.stream().sorted().toList();return sorted.get(Math.max(0,(int)Math.ceil(fraction*sorted.size())-1));
    }
    private static Row row(String mode,Query q,List<ProductVariant> products,long started) {
        double ms=(System.nanoTime()-started)/1_000_000.0;
        List<String> ids=products.stream().map(p->p.id).toList();
        return new Row(mode,q,ids.indexOf(q.relevant())+1,ms,ids.stream().limit(3).toList());
    }
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of("src/test/resources/search-evaluation.tsv");
        Path output=Path.of("docs/ai-search-evaluation");Files.createDirectories(output);
        List<Query> queries=new ArrayList<>();
        for(String line:Files.readAllLines(fixture,StandardCharsets.UTF_8)) {
            String[] fields=line.split("\\t",-1);
            if(fields.length!=3)throw new IllegalArgumentException("Expected query ID, text and labelled product");
            queries.add(new Query(fields[0],fields[1],fields[2]));
        }
        TechZoneStore store=new TechZoneStore();
        try(SemanticSearch semantic=new SemanticSearch()) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(190);
            while(!semantic.ready() && System.nanoTime()<deadline)Thread.sleep(250);
            if(!semantic.ready() || !"BGE-M3".equals(semantic.modelName())) throw new IllegalStateException("BGE-M3 is not ready");
            List<SemanticSearch.Document> documents=store.products().stream().filter(p->p.active).map(p->new SemanticSearch.Document(p.id,
                    p.name+" "+p.sku+" "+p.category+" "+p.brand+" "+p.description+" "+p.specs)).toList();
            for(Query q:queries) if(store.product(q.relevant())==null || store.available(q.relevant())<=0)
                throw new IllegalStateException("Label is missing or unavailable: "+q.relevant());
            if(semantic.rank("warm up",documents,120000)==null)throw new IllegalStateException("BGE-M3 warmup failed");
            List<Row> rows=new ArrayList<>();
            for(Query q:queries) {
                long start=System.nanoTime();
                rows.add(row("keyword",q,store.search(q.text(),"","","",0,false),start));
                start=System.nanoTime();
                Map<String,Double> scores=semantic.rank(q.text(),documents,120000);
                if(scores==null || scores.size()!=documents.size())throw new IllegalStateException("Semantic inference failed: "+q.id());
                rows.add(row("BGE-M3",q,store.searchWithEmbeddings(q.text(),"","","",0,Map.of(),scores),start));
            }
            StringBuilder detail=new StringBuilder("mode\tquery_id\tquery\tlabelled_product\trank\tlatency_ms\ttop3\n");
            for(Row r:rows)detail.append(String.join("\t",r.mode(),r.query().id(),clean(r.query().text()),r.query().relevant(),
                    Integer.toString(r.rank()),String.format(Locale.ROOT,"%.3f",r.milliseconds()),String.join(",",r.top3()))).append('\n');
            Files.writeString(output.resolve("results.tsv"),detail,StandardCharsets.UTF_8);
            StringBuilder summary=new StringBuilder("mode\tqueries\tcatalogue_active\trecall_at_3\tndcg_at_3\tmedian_ms\tp95_ms\n");
            for(String mode:List.of("keyword","BGE-M3")) {
                List<Row> group=rows.stream().filter(r->r.mode().equals(mode)).toList();
                double recall=group.stream().filter(r->r.rank()>0 && r.rank()<=3).count()/(double)group.size();
                double dcg=group.stream().mapToDouble(r->ndcg(r.rank())).average().orElseThrow();
                List<Double> times=group.stream().map(Row::milliseconds).toList();
                summary.append(String.format(Locale.ROOT,"%s\t%d\t%d\t%.4f\t%.4f\t%.3f\t%.3f%n",mode,group.size(),documents.size(),recall,dcg,percentile(times,.5),percentile(times,.95)));
            }
            Files.writeString(output.resolve("summary.tsv"),summary,StandardCharsets.UTF_8);
            String metadata="timestamp="+OffsetDateTime.now()+"\njava="+System.getProperty("java.version")+"\nos="+System.getProperty("os.name")+" "+System.getProperty("os.arch")
                    +"\nmodel=BGE-M3\nrevision=5617a9f61b028005a4858fdac845db406aefb181\nfixture="+fixture+"\ncatalogue=TechZoneStore seed (mock)\nmeasurement=in-process; warm documents; single run per query; no HTTP\n";
            Files.writeString(output.resolve("metadata.txt"),metadata,StandardCharsets.UTF_8);
            System.out.print(summary);System.out.println("Detailed results: "+output.resolve("results.tsv"));
        } finally { store.closeRepository(); }
    }
}
