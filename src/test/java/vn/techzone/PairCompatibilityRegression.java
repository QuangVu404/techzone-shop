package vn.techzone;

import vn.techzone.application.*;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import vn.techzone.domain.TechZoneModels.Role;
import vn.techzone.backend.AdvisorService;
import java.util.*;

public class PairCompatibilityRegression {
    private static ProductVariant item(String id,String category,Map<String,String> specs) {
        return new ProductVariant(id,id,id,category,"brand","","",100,0,0,specs);
    }
    private static void check(boolean v,String m) { if(!v)throw new AssertionError(m); }
    public static void main(String[] args) {
        var cpu=item("cpu","CPU",Map.of("socket","AM5"));
        var yes=item("yes","Mainboard",Map.of("socket","AM5"));
        var no=item("no","Mainboard",Map.of("socket","AM4"));
        var missing=item("unknown","Mainboard",Map.of());
        check(PairCompatibility.assess(cpu,yes).status()==PairCompatibility.Status.MATCH,"matching socket");
        check(PairCompatibility.assess(cpu,no).status()==PairCompatibility.Status.CONFLICT,"mismatched socket");
        check(PairCompatibility.assess(cpu,missing).status()==PairCompatibility.Status.UNKNOWN,"missing data stays unknown");
        var nvme=item("ssd","SSD",Map.of("interface","M.2 NVMe"));
        var m2=item("board","Mainboard",Map.of("m2","2 khe M.2"));
        check(PairCompatibility.assess(m2,nvme).status()==PairCompatibility.Status.UNKNOWN,"M.2 form factor does not prove NVMe support");
        TechZoneStore store=new TechZoneStore();
        try {
            store.importRealCatalog();
            check(store.assessPair("GPU-4060","PCP-CVkD4D").status()==PairCompatibility.Status.MATCH,"new product matched from recorded PSU wattage without a seeded pair");
            check(store.eligibleRecommendations("GPU-4060",Set.of()).stream().noneMatch(p->p.id.equals("PCP-CVkD4D")),"unreviewed pair must not reach ranking");
            store.flagCompatibility("GPU-4060","PCP-CVkD4D","Review sourced specifications",Role.SUPPORT,"test");
            var review=store.compatibilityReviews().stream().filter(r->r.primaryVariantId.equals("GPU-4060") && r.accessoryVariantId.equals("PCP-CVkD4D") && r.status.equals("Chờ rà soát")).findFirst().orElseThrow();
            check(store.reviewCompatibility(review.id,true,Role.SUPPORT,"test","Manually reviewed manufacturer specifications").startsWith("Đã"),"verified pair can be approved without hard-coded list changes");
            check(store.eligibleRecommendations("GPU-4060",Set.of()).stream().anyMatch(p->p.id.equals("PCP-CVkD4D")),"approved pair reaches ranking");
            check(store.assessPair("GPU-4070S","PSU-550").status()==PairCompatibility.Status.CONFLICT,"explicitly flagged pair stays blocked");
            var reviewedGpu=store.product("GPU-4070S");String minPsu=reviewedGpu.spec("minPsu");
            store.flagCompatibility("GPU-4070S","PSU-750","Verify manufacturer specifications",Role.SUPPORT,"test");
            var pending=store.compatibilityReviews().stream().filter(r->r.primaryVariantId.equals("GPU-4070S") && r.accessoryVariantId.equals("PSU-750") && r.status.equals("Chờ rà soát")).findFirst().orElseThrow();
            reviewedGpu.specs.put("minPsu","");
            check(store.reviewCompatibility(pending.id,true,Role.SUPPORT,"test","source").startsWith("Không thể") && pending.status.equals("Chờ rà soát"),"missing data remains pending for correction");
            reviewedGpu.specs.put("minPsu",minPsu);
            check(store.reviewCompatibility(pending.id,true,Role.SUPPORT,"test","Manufacturer specifications reviewed").startsWith("Đã"),"review can resume after data correction");
            store.addToCart("MB-B760-D5",1); store.addToCart("SSD-990",1);
            check(!store.validateCart().isEmpty() && store.cartCompatibilityWarning().contains("Chưa được duyệt"),"unverified pair blocks checkout");
            var build=AdvisorService.answer(store,"PC chơi game 50 triệu","",Map.of("PCP-CVkD4D",1.0));
            check(build.configuration() && build.products().size()==8 && build.products().stream().anyMatch(p->p.category.equals("Thùng máy")),"configuration includes case from full catalogue");
            check(build.reply().contains("Chưa xác minh") && build.reply().contains("Đối chiếu linh kiện"),"missing technical data is visible");
            for(int i=0;i<build.products().size();i++) for(int j=i+1;j<build.products().size();j++)
                check(store.assessPair(build.products().get(i).id,build.products().get(j).id).status()!=PairCompatibility.Status.CONFLICT,"build excludes known conflicts");
            store.updateCartQuantity("MB-B760-D5",0);store.updateCartQuantity("SSD-990",0);
            var gpu=store.product("GPU-4060");var chassis=store.product("PCP-Y6Crxr");
            gpu.specs.put("gpuLengthMm","310");gpu.specs.put("source.gpuLengthMm","https://example.com/gpu");
            chassis.specs.put("maxGpuLengthMm","300");chassis.specs.put("source.maxGpuLengthMm","https://example.com/case");
            store.addToCart(gpu.id,1);store.addToCart(chassis.id,1);
            check(store.validateCart().contains("Chiều dài GPU"),"physical clearance conflict blocks checkout");
            store.updateCartQuantity(gpu.id,0);store.updateCartQuantity(chassis.id,0);
            store.addToCart("CPU-14600K",1);store.addToCart("MB-B760-D4",1);
            check(store.validateCart().isEmpty() && store.cartCompatibilityWarning().contains("BIOS"),"unknown BIOS is visible without inventing a version");
            System.out.println("Dynamic compatibility: match, conflict, unknown and newly imported product passed");
        } catch(Exception e) { throw new RuntimeException(e); }
        finally { store.closeRepository(); }
    }
}
