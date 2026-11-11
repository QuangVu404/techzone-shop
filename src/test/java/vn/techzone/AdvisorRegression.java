package vn.techzone;

import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.AdvisorService;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

public final class AdvisorRegression {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static ProductVariant item(AdvisorService.Answer a,String category) {
        return a.products().stream().filter(p -> p.category.equals(category)).findFirst().orElseThrow();
    }
    public static void main(String[] args) throws Exception {
        TechZoneStore store=new TechZoneStore();
        try {
            check(AdvisorService.budget("30 triệu")==30_000_000L && AdvisorService.budget("dưới 25tr")==25_000_000L
                    && AdvisorService.budget("35.000.000 đồng")==35_000_000L && AdvisorService.budget("25,5 triệu")==25_500_000L,"budget parsing");
            check(AdvisorService.budget("khoảng ba mươi triệu")==30_000_000 && AdvisorService.budget("tôi có ngân sách")==0,"do not infer an unstated budget");
            var ask=AdvisorService.answer(store,"Tôi muốn cấu hình PC chơi game","",null);
            check(ask.configuration() && ask.budget()==0 && ask.products().isEmpty(),"ask for missing budget");
            var proposal=AdvisorService.answer(store,"35 triệu","Tôi muốn cấu hình PC chơi game",null);
            check(proposal.configuration() && proposal.products().size()==7 && proposal.total()+proposal.total()/10<=proposal.budget(),"follow-up budget and complete supported selection");
            var cpu=item(proposal,"CPU");var board=item(proposal,"Mainboard");var ram=item(proposal,"RAM");
            var gpu=item(proposal,"GPU");var psu=item(proposal,"PSU");var disk=item(proposal,"SSD");var cooler=item(proposal,"Tản nhiệt");
            check(store.approvedCompatible(cpu.id,board.id) && store.approvedCompatible(board.id,ram.id)
                    && store.approvedCompatible(board.id,disk.id) && store.approvedCompatible(gpu.id,psu.id)
                    && store.approvedCompatible(cpu.id,cooler.id),"all recommendation pairs are approved and match current facts");
            check(proposal.products().stream().allMatch(p->p.active && store.available(p.id)>0),"only available products");
            var prefer4060=AdvisorService.answer(store,"PC chơi game 50 triệu","",Map.of("GPU-4060",1.0,"GPU-4070S",0.0));
            var prefer4070=AdvisorService.answer(store,"PC chơi game 50 triệu","",Map.of("GPU-4060",0.0,"GPU-4070S",1.0));
            check(item(prefer4060,"GPU").id.equals("GPU-4060") && item(prefer4070,"GPU").id.equals("GPU-4070S"),"semantic scores change selection among eligible configurations");
            var equalScores=AdvisorService.answer(store,"PC chơi game 50 triệu","",Map.of());
            var economical=AdvisorService.answer(store,"PC chơi game 50 triệu","",null);
            check(equalScores.total()==economical.total(),"equal semantic scores do not reward a more expensive build");
            var low=AdvisorService.answer(store,"PC gaming dưới 5 triệu","",null);
            check(low.products().isEmpty() && low.reply().contains("ngân sách"),"do not exceed budget");
            var ramSearch=AdvisorService.answer(store,"Tôi cần RAM dưới 5 triệu","",null);
            check(!ramSearch.products().isEmpty() && ramSearch.products().stream().allMatch(p -> p.category.equals("RAM") && p.effectivePrice()<=5_000_000),"category advice");
            var memory=AdvisorService.answer(store,"Tìm RAM DDR5 32GB","",null);
            check(!memory.products().isEmpty() && memory.products().stream().allMatch(p -> p.spec("memoryType").equals("DDR5") && p.spec("capacity").equals("32 GB")),"capacity and type are hard filters");
            var graphics=AdvisorService.answer(store,"Tìm RTX 4060","",null);
            check(!graphics.products().isEmpty() && graphics.products().stream().allMatch(p -> p.category.equals("GPU") && p.name.contains("4060")),"graphics model filter");
            var amd=AdvisorService.answer(store,"PC game AMD 50 triệu","",null);
            check(!amd.products().isEmpty() && item(amd,"CPU").brand.equals("AMD"),"honor requested CPU brand");
            var noGpu=AdvisorService.answer(store,"PC văn phòng 50 triệu không cần GPU","",null);
            check(noGpu.products().isEmpty() && noGpu.reply().contains("chưa có đủ dữ liệu"),"never silently ignore excluded GPU");
            var constrained=AdvisorService.answer(store,"PC game 50 triệu RAM DDR4 16GB RTX 4060","",null);
            check(!constrained.products().isEmpty() && item(constrained,"RAM").spec("memoryType").equals("DDR4")
                    && item(constrained,"RAM").spec("capacity").equals("16 GB") && item(constrained,"GPU").name.contains("4060"),"build honors explicit RAM and GPU requirements");
            check(AdvisorService.answer(store,"PC game 50 triệu RAM DDR5 128GB","",null).products().isEmpty(),"unavailable RAM requirement must not be silently ignored");
            check(AdvisorService.answer(store,"PC game 50 triệu RTX 4060 Ti","",null).products().isEmpty(),"do not substitute another GPU model");
            var laptop=AdvisorService.answer(store,"tôi muốn laptop 20 triệu","",null);
            check(laptop.products().isEmpty() && laptop.reply().contains("chưa có laptop"),"no fabricated products");
            var order=AdvisorService.answer(store,"đơn hàng của tôi đang ở đâu","",null);
            check(order.products().isEmpty() && order.reply().contains("tài khoản hiện tại"),"order lookup uses the signed-in customer");
            var specificOrder=AdvisorService.answer(store,"đơn hàng ORD-1038 đang ở đâu","",null);
            check(specificOrder.reply().contains("1038") && specificOrder.reply().contains("đang giao"),"specific order must not be replaced by the latest");
            check(AdvisorService.answer(store,"đơn hàng ORD-999999","",null).reply().contains("Không tìm thấy"),"unknown order must not return a different order");
            var warranty=AdvisorService.answer(store,"tra cứu bảo hành theo serial","",null);
            check(warranty.reply().contains("Bảo hành & RMA"),"warranty guidance");
            store.balance("SSD-990").available=0;
            check(AdvisorService.answer(store,"PC gaming 50 triệu","",null).products().isEmpty(),"missing stock must not produce an unsupported build");
            check(AdvisorService.answer(store,"Samsung 990 EVO Plus 1TB NVMe","",null).products().size()==1,
                    "an exact product can still be looked up when it is out of stock");
            List<TechZoneStore.GearVNCatalogProduct> listings=new ArrayList<>();
            for(int i=0;i<50;i++) listings.add(new TechZoneStore.GearVNCatalogProduct(
                    String.format("GVN-%016x",i),"GearVN Test CPU "+i,"CPU","Intel","test",
                    1_000_000,0,"SKU-"+i,"UNKNOWN","https://gearvn.com/products/test-"+i,
                    "https://cdn.hstatic.net/test.png",null,Map.of()));
            store.syncGearVNCatalog(listings);
            var external=store.product("GVN-0000000000000000");
            check(store.available(external.id)==0 && store.search("Test CPU 0","CPU","","",0,false).stream().anyMatch(p->p.id.equals(external.id)),
                    "external products are searchable without changing local stock");
            var externalAdvice=AdvisorService.answer(store,"Tìm CPU dưới 5 triệu","",null);
            check(!externalAdvice.products().isEmpty() && externalAdvice.products().stream().allMatch(p->p.spec("sourceProvider").equals("GEARVN")),
                    "category advice can display external references");
            check(AdvisorService.answer(store,"PC chơi game 50 triệu","",null).products().isEmpty(),
                    "external references must not become a verified build");
            System.out.println("Advisor: budget, context, compatibility, stock, missing data and category advice passed");
        } finally { store.closeRepository(); }
    }
}
