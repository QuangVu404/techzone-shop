package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;
import java.util.regex.*;

/** Suggests related components for review; never grants compatibility approval. */
public final class ProvisionalCompatibilityService {
    public record Candidate(ProductVariant product,String status,String reason) { }
    private static final Map<String,Set<String>> RELATED=Map.of(
            "CPU",Set.of("Mainboard","Tản nhiệt"),
            "Mainboard",Set.of("CPU","RAM","SSD","Case"),
            "RAM",Set.of("Mainboard"),
            "GPU",Set.of("PSU","Case"),
            "PSU",Set.of("GPU","Case"),
            "SSD",Set.of("Mainboard","Case"),
            "Tản nhiệt",Set.of("CPU","Case"),
            "Case",Set.of("Mainboard","GPU","PSU","Tản nhiệt","SSD"));

    private static String raw(ProductVariant p,String key) {
        if(!"GEARVN".equals(p.spec("sourceProvider")) || !"OK".equals(p.spec("sourceSpecStatus"))
                || p.spec("sourceUrl").isBlank()) return "";
        return p.spec("Thông số · "+key);
    }
    private static String socket(ProductVariant p) {
        String raw=raw(p,"Socket").toUpperCase(Locale.ROOT).replaceAll("\\s+","");
        return raw.matches("AM[1-9]|LGA[0-9]{3,4}")?raw:"";
    }
    private static String memory(ProductVariant p) {
        String raw=raw(p,p.category.equals("RAM")?"Loại RAM":"Kiểu RAM hỗ trợ").toUpperCase(Locale.ROOT).replaceAll("\\s+","");
        return raw.matches("DDR[345]")?raw:"";
    }
    private static Integer watts(String text) {
        Matcher m=Pattern.compile("(?i)\\b(\\d{3,4})\\s*w\\b").matcher(text);
        return m.find()?Integer.parseInt(m.group(1)):null;
    }
    private static ProductVariant of(ProductVariant a,ProductVariant b,String category) {
        return a.category.equals(category)?a:b.category.equals(category)?b:null;
    }
    private static Candidate compare(ProductVariant a,ProductVariant b) {
        ProductVariant cpu=of(a,b,"CPU"), board=of(a,b,"Mainboard"), ram=of(a,b,"RAM");
        ProductVariant cooler=of(a,b,"Tản nhiệt"), gpu=of(a,b,"GPU"), psu=of(a,b,"PSU"), box=of(a,b,"Case");
        String reason="Chưa đủ dữ liệu để xác nhận; cần đối chiếu thông số và kiểm tra kỹ thuật.";
        if(cpu!=null && board!=null) {
            String x=socket(cpu),y=socket(board);
            if(!x.isEmpty() && !y.isEmpty()) return x.equals(y)
                    ? new Candidate(b,"PARTIAL","Socket "+x+" khớp theo dữ liệu danh mục; cần kiểm tra CPU hỗ trợ và BIOS.") : null;
        } else if(board!=null && ram!=null) {
            String x=memory(board),y=memory(ram);
            if(!x.isEmpty() && !y.isEmpty()) return x.equals(y)
                    ? new Candidate(b,"PARTIAL","Cùng loại "+x+"; cần kiểm tra dung lượng, tốc độ và danh sách RAM hỗ trợ.") : null;
        } else if(cpu!=null && cooler!=null) {
            String x=socket(cpu), support=raw(cooler,"Tương thích CPU").toUpperCase(Locale.ROOT).replaceAll("\\s+","");
            Set<String> sockets=new HashSet<>(); Matcher m=Pattern.compile("LGA\\d{3,4}|AM[1-9]").matcher(support);
            while(m.find())sockets.add(m.group());
            if(!x.isEmpty() && !sockets.isEmpty()) return sockets.contains(x)
                    ? new Candidate(b,"PARTIAL","Tản nhiệt ghi hỗ trợ "+x+"; cần kiểm tra ngàm và kích thước thực tế.") : null;
        } else if(gpu!=null && psu!=null) {
            Integer required=watts(raw(gpu,"Nguồn đề xuất")), supplied=watts(raw(psu,"Công suất"));
            if(supplied==null) supplied=watts(raw(psu,"Công suất theo hãng"));
            if(required!=null && supplied!=null) return supplied>=required
                    ? new Candidate(b,"PARTIAL","Nguồn "+supplied+" W so với mức GPU đề xuất "+required+" W; cần kiểm tra toàn bộ tải và đầu cắm.") : null;
        } else if(board!=null && box!=null) {
            String form=raw(board,"Kích thước"), support=raw(box,"Hỗ trợ main");
            String norm=form.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
            if(!norm.isEmpty() && !support.isBlank() && Set.of("ATX","MATX","MICROATX","MINIITX","EATX").contains(norm)) {
                Set<String> forms=new HashSet<>();
                for(String item:support.split("[,;/]")) forms.add(item.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""));
                if(norm.equals("MATX"))norm="MICROATX";
                if(forms.remove("MATX"))forms.add("MICROATX");
                return forms.contains(norm)?new Candidate(b,"PARTIAL","Vỏ ghi hỗ trợ chuẩn mainboard "+form+"; cần đối chiếu vị trí lắp và kích thước."):null;
            }
        }
        return new Candidate(b,"UNKNOWN",reason);
    }
    public List<Candidate> candidates(ProductVariant primary,Collection<ProductVariant> catalogue,Set<String> selected,
                                      ToIntFunction<String> available,BiFunction<String,String,PairCompatibility.Result> assess) {
        if(primary==null || !primary.active)return List.of();
        Set<String> targets=RELATED.getOrDefault(primary.category,Set.of()), cart=selected==null?Set.of():selected;
        Map<String,ProductVariant> byId=new HashMap<>(); for(ProductVariant p:catalogue)byId.put(p.id,p);
        List<Candidate> out=new ArrayList<>();
        for(ProductVariant candidate:catalogue) {
            if(!candidate.active || candidate.id.equals(primary.id) || cart.contains(candidate.id) || available.applyAsInt(candidate.id)<=0
                    || !targets.contains(candidate.category) || "REVIEW".equals(candidate.spec("sourceQualityStatus")))continue;
            if(assess.apply(primary.id,candidate.id).status()==PairCompatibility.Status.CONFLICT)continue;
            boolean blocked=false;
            for(String id:cart) {
                ProductVariant item=byId.get(id);
                if(item==null || (CompatibilityPolicy.needsReviewedCartPair(item,candidate)
                        && assess.apply(id,candidate.id).status()==PairCompatibility.Status.CONFLICT)) { blocked=true;break; }
            }
            if(blocked)continue;
            Candidate result=compare(primary,candidate);
            if(result!=null)out.add("REVIEW".equals(primary.spec("sourceQualityStatus"))
                    ? new Candidate(candidate,"UNKNOWN","Thông tin sản phẩm chính đang cần xác minh; chưa thể kết luận tương thích.") : result);
        }
        return List.copyOf(out);
    }
}
