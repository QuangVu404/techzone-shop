package vn.techzone;

import vn.techzone.application.*;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ProvisionalCompatibilityRegression {
    private static void check(boolean value,String text) { if(!value)throw new AssertionError(text); }
    public static void main(String[] args) throws Exception {
        List<TechZoneStore.GearVNCatalogProduct> listings=new ArrayList<>();
        for(String line:Files.readAllLines(Path.of("src/main/resources/vn/techzone/catalog/gearvn-catalog.tsv"))) {
            String[] encoded=line.split("\\t",-1), f=new String[encoded.length];
            for(int i=0;i<f.length;i++)f[i]=new String(Base64.getDecoder().decode(encoded[i]),StandardCharsets.UTF_8);
            Map<String,String> specs=new LinkedHashMap<>();for(int i=12;i<f.length;i+=2)specs.put(f[i],f[i+1]);
            listings.add(new TechZoneStore.GearVNCatalogProduct(f[0],f[1],f[2],f[3],f[4],Long.parseLong(f[5]),Long.parseLong(f[6]),f[7],f[8],f[9],f[10],null,specs));
        }
        TechZoneStore store=new TechZoneStore();
        try {
            store.syncGearVNCatalog(listings);
            for(var listing:listings)store.balance(listing.id()).available=1;
            ProvisionalCompatibilityService service=new ProvisionalCompatibilityService();
            int covered=0,partial=0;
            for(var listing:listings) {
                ProductVariant p=store.product(listing.id());
                var results=service.candidates(p,store.products(),Set.of(),store::available,store::assessPair);
                if(!results.isEmpty())covered++;
                partial+=(int)results.stream().filter(x->x.status().equals("PARTIAL")).count();
                for(var c:results) {
                    check(!c.product().id.equals(p.id) && store.available(c.product().id)>0,"inactive or identical candidate");
                    check(!c.product().spec("sourceQualityStatus").equals("REVIEW"),"uncertain catalogue item recommended");
                    check(store.assessPair(p.id,c.product().id).status()!=PairCompatibility.Status.CONFLICT,"known conflict recommended");
                    check(c.status().equals("PARTIAL") || c.status().equals("UNKNOWN"),"unexpected evidence status");
                    if(p.spec("sourceQualityStatus").equals("REVIEW"))
                        check(c.status().equals("UNKNOWN"),"uncertain primary must not appear partially verified");
                }
            }
            check(covered==200,"Expected candidates across all 200 products; actual "+covered);
            check(partial>0,"Expected some sourced partial checks");
            var primary=store.products().stream().filter(p->p.active && p.category.equals("CPU") && p.spec("socket").equals("AM5")).findFirst().orElseThrow();
            var list=service.candidates(primary,store.products(),Set.of(),store::available,store::assessPair);
            var match=list.stream().filter(c->c.product().category.equals("Mainboard") && c.status().equals("PARTIAL")).findFirst().orElseThrow();
            check(!store.approvedCompatible(primary.id,match.product().id),"provisional suggestion must not approve a pair");
            check(service.candidates(primary,store.products(),Set.of(match.product().id),store::available,store::assessPair).stream()
                    .noneMatch(c->c.product().id.equals(match.product().id)),"cart products must not be suggested again");
            System.out.println("Provisional compatibility: 200/200 products covered; conflicts excluded; approval unchanged");
        } finally { store.closeRepository(); }
    }
}
