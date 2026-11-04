package vn.techzone.backend;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.PairCompatibility;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Catalogue-grounded PC suggestions. Missing compatibility never becomes a positive claim. */
public final class AdvisorService {
    public record Answer(String reply, List<ProductVariant> products, long total, long budget, boolean configuration) { }
    private AdvisorService() { }

    private static String normalized(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);
    }
    public static long budget(String text) {
        String s=normalized(text);
        Matcher m=Pattern.compile("(?<![a-z0-9])(\\d+(?:[.,]\\d{1,2})?)\\s*(?:trieu|tr|cu|million|mil|m)(?![a-z])").matcher(s);
        long found=0;
        while(m.find()) {
            try { double n=Double.parseDouble(m.group(1).replace(',','.')); if(n>0 && n<=1000) found=(long)(n*1_000_000); }
            catch(NumberFormatException ignored) { }
        }
        if(found>0) return found;
        m=Pattern.compile("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3}){2}|\\d{7,10})\\s*(?:vnd|dong|d)?(?!\\d)").matcher(s);
        while(m.find()) try { long n=Long.parseLong(m.group(1).replaceAll("[.,]","")); if(n>0) found=n; }
        catch(NumberFormatException ignored) { }
        if(found==0) {
            Matcher words=Pattern.compile("\\b((?:mot|hai|ba|bon|tu|nam|sau|bay|tam|chin|muoi|tram|linh|le|lam|mot)(?:\\s+(?:mot|hai|ba|bon|tu|nam|sau|bay|tam|chin|muoi|tram|linh|le|lam))*)\\s+(trieu|cu|ty|ti)\\b").matcher(s);
            Map<String,Integer> digits=Map.ofEntries(Map.entry("mot",1),Map.entry("hai",2),Map.entry("ba",3),Map.entry("bon",4),Map.entry("tu",4),Map.entry("nam",5),Map.entry("lam",5),Map.entry("sau",6),Map.entry("bay",7),Map.entry("tam",8),Map.entry("chin",9));
            while(words.find()) {
                int value=0,current=0;
                for(String word:words.group(1).split("\\s+")) {
                    if(word.equals("tram")) { value+=(current==0?1:current)*100; current=0; }
                    else if(word.equals("muoi")) { value+=(current==0?1:current)*10; current=0; }
                    else if(digits.containsKey(word)) current=digits.get(word);
                }
                long amount=(long)(value+current)*(words.group(2).equals("ty")||words.group(2).equals("ti")?1_000_000_000L:1_000_000L);
                if(amount>0 && amount<=1_000_000_000L) found=amount;
            }
        }
        return found;
    }
    private static List<ProductVariant> category(TechZoneStore store, String name) {
        return store.products().stream().filter(p -> p.active && p.category.equals(name) && store.available(p.id)>0)
                .sorted(Comparator.comparingLong(ProductVariant::effectivePrice).thenComparing(p->p.id)).toList();
    }
    private record Candidate(List<ProductVariant> items,long total,double relevance,int unknown) { }
    private static double candidateScore(Candidate c,Map<String,Double> scores) {
        return scores==null ? -c.total() : c.relevance()/c.items().size()-0.02*c.unknown();
    }
    private static Comparator<Candidate> candidatesByScore(Map<String,Double> scores) {
        if(scores==null) return Comparator.comparingInt(Candidate::unknown).thenComparingLong(Candidate::total)
                .thenComparing(c->c.items().stream().map(p->p.id).reduce("",(a,b)->a+"|"+b));
        return Comparator.comparingDouble((Candidate c)->candidateScore(c,scores)).reversed()
                .thenComparingLong(Candidate::total)
                .thenComparing(c->c.items().stream().map(p->p.id).reduce("",(a,b)->a+"|"+b));
    }
    private static int capacityGb(String text) {
        String normalized=normalized(text);
        Matcher modules=Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[x×]\\s*(\\d{1,4})\\s*(gb|tb)\\b").matcher(normalized);
        if(modules.find()) return Integer.parseInt(modules.group(1))*Integer.parseInt(modules.group(2))*(modules.group(3).equals("tb")?1024:1);
        Matcher capacity=Pattern.compile("(?<!\\d)(\\d{1,5})\\s*(gb|tb)\\b").matcher(normalized);
        if(capacity.find()) return Integer.parseInt(capacity.group(1))*(capacity.group(2).equals("tb")?1024:1);
        return 0;
    }
    private static int productCapacityGb(ProductVariant p) {
        for(String key:List.of("capacity","Capacity","vram","Memory","Modules")) {
            int value=capacityGb(p.spec(key)); if(value>0) return value;
        }
        return capacityGb(p.name);
    }
    private static boolean mentionsProduct(String text,ProductVariant product) {
        String name=normalized(product.name);
        if(name.length()>=8 && Pattern.compile("(?<![a-z0-9])"+Pattern.quote(name)+"(?![a-z0-9])").matcher(text).find()) return true;
        String sku=normalized(product.sku);
        return sku.length()>=6 && Pattern.compile("(?<![a-z0-9])"+Pattern.quote(sku)+"(?![a-z0-9])").matcher(text).find();
    }
    public static Answer answer(TechZoneStore store, String message, String context, Map<String,Double> semanticScores) {
        String query=normalized(message), history=normalized(context+" "+message);
        long limit=budget(context+" "+message);
        if(query.matches("(?s).*\\b(don hang|van don|giao hang cua toi)\\b.*")) {
            var orders=store.ordersForCustomer(store.signedInCustomerId());
            if(orders.isEmpty()) return new Answer("Tài khoản hiện tại chưa có đơn hàng. Bạn có thể xem mục Đơn hàng & giao hàng để theo dõi.",List.of(),0,limit,false);
            Matcher explicit=Pattern.compile("\\b(?:tzo-[a-z0-9-]+|ord-[a-z0-9-]+|tzvn[a-z0-9]+)\\b|\\bdon hang\\s+(?:so\\s+|ma\\s+)?#?(\\d{3,})\\b").matcher(query);
            boolean requested=explicit.find(); String identifier=requested?explicit.group():"";
            if(requested && explicit.group(1)!=null) identifier=explicit.group(1);
            final String code=identifier;
            var order=requested?orders.stream().filter(o -> normalized(o.id).equals(code) || normalized(o.number).equals(code) || normalized(o.trackingCode).equals(code)
                    || normalized(o.id).endsWith("-"+code) || normalized(o.number).endsWith("-"+code)).findFirst().orElse(null):orders.get(0);
            if(order==null) return new Answer("Không tìm thấy đơn hàng đó trong tài khoản hiện tại. Hãy kiểm tra lại mã đơn.",List.of(),0,limit,false);
            String label=switch(order.status) {
                case PENDING_PAYMENT -> "chờ đối soát thanh toán"; case PENDING_REVIEW -> "chờ xác minh";
                case CONFIRMED -> "đã xác nhận"; case PACKED -> "đã đóng gói";
                case SHIPPED -> "đang giao"; case DELIVERED -> "đã giao"; case CANCELLED -> "đã hủy";
            };
            return new Answer((requested?"Đơn hàng ":"Đơn gần nhất của tài khoản hiện tại là ")+order.number+": "+label+". Mở mục Đơn hàng & giao hàng để xem chi tiết.",List.of(),0,limit,false);
        }
        boolean reference=query.matches("(?s).*\\b(no|san pham nay|cai nay|loai nay)\\b.*");
        String namingText=reference ? normalized(context+" "+message) : query;
        List<ProductVariant> mentioned=store.products().stream().filter(p -> p.active)
                .filter(p -> mentionsProduct(namingText,p))
                .sorted(Comparator.comparingInt((ProductVariant p)->p.name.length()).reversed()).toList();
        if(reference && mentioned.size()>1)
            return new Answer("Bạn muốn hỏi sản phẩm nào trong các sản phẩm vừa nhắc? Hãy gửi tên hoặc mã sản phẩm.",List.of(),0,limit,false);
        if(!reference && mentioned.size()>1 && !query.matches(".*\\b(build|cau hinh|may tinh|pc)\\b.*"))
            return new Answer("Mình tìm thấy các sản phẩm này; hãy mở chi tiết hoặc so sánh thông số trước khi chọn.",mentioned.stream().limit(4).toList(),0,limit,false);
        ProductVariant named=mentioned.isEmpty()?null:mentioned.get(0);
        if(named!=null && !query.matches(".*\\b(build|cau hinh|may tinh|pc)\\b.*"))
            return new Answer("Mình tìm thấy sản phẩm này trong danh mục. Mở chi tiết để xem thông số và nguồn dữ liệu.",List.of(named),0,limit,false);
        if(query.matches("(?s).*\\b(bao hanh|serial|imei|sua chua)\\b.*"))
            return new Answer("Bạn có thể mở mục Bảo hành & RMA để tra cứu Serial/IMEI hoặc gửi yêu cầu xác minh. Thời hạn chưa có nguồn sẽ không được tự khẳng định.",List.of(),0,limit,false);
        if(query.matches("(?s).*\\b(tuong thich|hop nhau|co phu hop khong)\\b.*"))
            return new Answer("Bạn hãy mở chi tiết sản phẩm hoặc chọn linh kiện trong giỏ để xem những thông số đã đối chiếu. Các điều kiện thiếu vẫn cần được kỹ thuật kiểm tra.",List.of(),0,limit,false);
        if(query.matches(".*\\b(laptop|notebook|may tinh xach tay)\\b.*")) {
            List<ProductVariant> laptops=category(store,"Laptop").stream().filter(p -> limit==0 || p.effectivePrice()<=limit).limit(4).toList();
            return new Answer(laptops.isEmpty()?"Hiện chưa có laptop còn hàng phù hợp trong danh mục. Bạn muốn mình tư vấn máy tính bàn hay linh kiện riêng?"
                    :"Đây là các laptop đang có trong danh mục. Bạn có thể nói thêm mục đích sử dụng để mình tư vấn.",laptops,0,limit,false);
        }
        boolean commercialQuestion=query.matches("(?s).*\\b(gia|gia ban|bao nhieu tien|ton kho|con hang|bao hanh)\\b.*");
        if(!commercialQuestion && query.matches("(?s).*\\b(la gi|khac nhau|vi sao|tai sao|giai thich|so sanh|khac gi|nen chon gi|co tot khong|nen mua khong|co du khong)\\b.*"))
            return new Answer("Bạn có thể hỏi về sản phẩm hoặc cho mình biết mục đích dùng máy và ngân sách để gợi ý cấu hình.",List.of(),0,limit,false);
        boolean build=history.matches("(?s).*\\b(build|cau hinh|may tinh|gaming|choi game|choi tro choi|lien minh|valorant|minecraft|do hoa|thiet ke|photoshop|blender|render|dung phim|dung video|edit video|lam viec|van phong|hoc tap|hoc online|lap trinh|pc)\\b.*");
        String category = query.matches(".*\\b(cpu|bo xu ly)\\b.*") ? "CPU"
                : query.matches(".*\\b(ram|bo nho)\\b.*") ? "RAM"
                : query.matches(".*\\b(mainboard|motherboard|bo mach chu)\\b.*") ? "Mainboard"
                : query.matches(".*\\b(gpu|vga|card do hoa|rtx|gtx|radeon|rtx\\s*\\d{4}(?:ti|super)?|gtx\\s*\\d{4}(?:ti|super)?)\\b.*") ? "GPU"
                : query.matches(".*\\b(ssd|o cung)\\b.*") ? "SSD"
                : query.matches(".*\\b(psu|nguon)\\b.*") ? "PSU"
                : query.matches(".*\\b(tan nhiet|cooler)\\b.*") ? "Tản nhiệt"
                : query.matches(".*\\b(dien thoai|smartphone)\\b.*") ? "Điện thoại" : "";
        if(!category.isEmpty() && !query.matches(".*\\b(build|cau hinh|may tinh|pc)\\b.*")) {
            int requestedCapacity=capacityGb(query);
            String memoryType=query.contains("ddr5")?"DDR5":query.contains("ddr4")?"DDR4":"";
            String socket=query.contains("am5")?"AM5":query.contains("lga1700")?"LGA1700":"";
            Matcher graphicsModel=Pattern.compile("\\b(rtx|gtx)\\s*(\\d{4})(?:\\s*(ti|super))?\\b").matcher(query);
            String graphics=graphicsModel.find()?graphicsModel.group(1)+graphicsModel.group(2)+(graphicsModel.group(3)==null?"":graphicsModel.group(3)):"";
            Comparator<ProductVariant> order=semanticScores==null ? Comparator.comparingLong(ProductVariant::effectivePrice)
                    : Comparator.comparingDouble((ProductVariant p) -> semanticScores.getOrDefault(p.id,0.0)).reversed()
                        .thenComparingLong(ProductVariant::effectivePrice);
            List<ProductVariant> found=category(store,category).stream().filter(p -> limit==0 || p.effectivePrice()<=limit)
                    .filter(p -> requestedCapacity==0 || productCapacityGb(p)==requestedCapacity)
                    .filter(p -> memoryType.isEmpty() || p.spec("memoryType").equalsIgnoreCase(memoryType))
                    .filter(p -> socket.isEmpty() || p.spec("socket").equalsIgnoreCase(socket))
                    .filter(p -> graphics.isEmpty() || normalized(p.name+" "+p.spec("Chipset")).replaceAll("[^a-z0-9]","").contains(graphics))
                    .filter(p -> !category.equals("CPU") || (!query.contains("amd") || p.brand.equalsIgnoreCase("AMD"))
                            && (!query.contains("intel") || p.brand.equalsIgnoreCase("Intel")))
                    .sorted(order).limit(4).toList();
            return new Answer(found.isEmpty()?"Mình chưa tìm thấy sản phẩm còn hàng phù hợp với các điều kiện bạn đưa ra. Bạn có thể điều chỉnh nhu cầu hoặc ngân sách?"
                    :"Đây là một số sản phẩm còn hàng trong danh mục. Bạn có thể nói rõ mẫu máy, mục đích hoặc mức giá để chọn kỹ hơn.",found,0,limit,false);
        }
        if(!build && commercialQuestion) return new Answer("Bạn cho mình tên hoặc mã sản phẩm cần tra cứu để mình kiểm tra thông tin trong danh mục nhé.",List.of(),0,limit,false);
        if(!build) return new Answer("Bạn có thể hỏi về sản phẩm hoặc cho mình biết mục đích dùng máy và ngân sách để gợi ý cấu hình.",List.of(),0,limit,false);
        if(limit==0) return new Answer("Bạn dự kiến chi bao nhiêu cho bộ máy? Ví dụ: 30 triệu. Nếu có, hãy cho biết ứng dụng hoặc trò chơi bạn dùng nhiều nhất.",List.of(),0,0,true);
        if(history.matches("(?s).*\\b(khong can|khong dung|khong co|bo qua)\\s+(card do hoa|vga|gpu)\\b.*"))
            return new Answer("Mình chưa có đủ dữ liệu đồ họa tích hợp được xác minh để lập bộ máy không dùng card rời. Bạn có thể chọn CPU riêng hoặc yêu cầu kiểm tra kỹ thuật.",List.of(),0,limit,true);
        String preference=query.matches("(?s).*\\b(ryzen|intel|amd)\\b.*")?query:history;
        boolean wantAmd=preference.matches("(?s).*\\b(ryzen|amd)\\b.*");
        boolean wantIntel=preference.matches("(?s).*\\b(intel|cpu intel)\\b.*");
        if(wantAmd && wantIntel) return new Answer("Bạn muốn CPU AMD Ryzen hay Intel cho cấu hình này?",List.of(),0,limit,true);
        boolean ddr4=history.matches("(?s).*\\bddr4\\b.*"),ddr5=history.matches("(?s).*\\bddr5\\b.*");
        if(ddr4 && ddr5) return new Answer("Bạn muốn cấu hình dùng RAM DDR4 hay DDR5?",List.of(),0,limit,true);
        String wantedMemory=ddr5?"DDR5":ddr4?"DDR4":"";
        Matcher ramRequest=Pattern.compile("\\b(?:ram|bo nho)\\s*(?:ddr[45]\\s*)?(\\d{1,4})\\s*gb\\b|\\b(\\d{1,4})\\s*gb\\s*(?:ram|bo nho)\\b").matcher(history);
        int ramCapacity=ramRequest.find()?Integer.parseInt(ramRequest.group(1)!=null?ramRequest.group(1):ramRequest.group(2)):0;
        Matcher ssdRequest=Pattern.compile("\\b(?:ssd|o cung)\\s*(\\d{1,4})\\s*(gb|tb)\\b").matcher(history);
        int ssdCapacity=ssdRequest.find()?Integer.parseInt(ssdRequest.group(1))*(ssdRequest.group(2).equals("tb")?1024:1):0;
        Matcher graphicsRequest=Pattern.compile("\\b(rtx|gtx)\\s*(\\d{4})(?:\\s*(ti|super))?\\b").matcher(history);
        String requestedGpu=graphicsRequest.find()?graphicsRequest.group(1)+graphicsRequest.group(2)+(graphicsRequest.group(3)==null?"":graphicsRequest.group(3)):"";
        Matcher cpuRequest=Pattern.compile("\\b(i[3579]\\s*-?\\s*\\d{4,5}[a-z0-9]*|ryzen\\s*[3579]\\s*\\d{4}[a-z0-9]*)\\b").matcher(history);
        String requestedCpu=cpuRequest.find()?cpuRequest.group().replaceAll("[^a-z0-9]",""):"";
        List<String> stages=new ArrayList<>(List.of("CPU","Mainboard","RAM","GPU","PSU","SSD","Tản nhiệt"));
        if(!category(store,"Thùng máy").isEmpty()) stages.add("Thùng máy");
        List<Candidate> beam=List.of(new Candidate(List.of(),0,0,0));
        for(String stage:stages) {
            List<ProductVariant> options=category(store,stage).stream()
                    .filter(p -> !stage.equals("CPU") || ((!wantAmd || p.brand.equalsIgnoreCase("AMD")) && (!wantIntel || p.brand.equalsIgnoreCase("Intel")) && (requestedCpu.isEmpty() || normalized(p.name).replaceAll("[^a-z0-9]","").contains(requestedCpu))))
                    .filter(p -> !stage.equals("RAM") || ((wantedMemory.isEmpty() || p.spec("memoryType").equalsIgnoreCase(wantedMemory)) && (ramCapacity==0 || productCapacityGb(p)==ramCapacity)))
                    .filter(p -> !stage.equals("SSD") || ssdCapacity==0 || productCapacityGb(p)==ssdCapacity)
                    .filter(p -> !stage.equals("GPU") || requestedGpu.isEmpty() || normalized(p.name+" "+p.spec("Chipset")).replaceAll("[^a-z0-9]","").contains(requestedGpu)).toList();
            List<Candidate> next=new ArrayList<>();
            for(Candidate partial:beam) for(ProductVariant option:options) {
                long sum=partial.total()+option.effectivePrice();
                if(sum+sum/10+(sum<2_000_000?35_000:0)>limit)continue;
                int unknown=partial.unknown(); boolean conflict=false;
                for(ProductVariant old:partial.items()) {
                    var check=store.assessPair(old.id,option.id);
                    if(check.status()!=PairCompatibility.Status.NOT_APPLICABLE && !store.approvedCompatible(old.id,option.id)) { conflict=true; break; }
                }
                if(conflict)continue;
                List<ProductVariant> items=new ArrayList<>(partial.items());items.add(option);
                if(BuildChecks.evaluate(items).stream().anyMatch(c->c.status().equals("fail")))continue;
                next.add(new Candidate(List.copyOf(items),sum,partial.relevance()+(semanticScores==null?0:semanticScores.getOrDefault(option.id,0.0)),unknown));
            }
            next.sort(candidatesByScore(semanticScores));
            beam=List.copyOf(next.subList(0,Math.min(256,next.size())));
            if(beam.isEmpty())break;
        }
        if(beam.isEmpty() || beam.get(0).items().size()!=stages.size())
            return new Answer("Chưa tìm được bộ linh kiện trong ngân sách có các cặp được duyệt và đủ thông số. Bạn có thể điều chỉnh nhu cầu hoặc yêu cầu kỹ thuật rà soát.",List.of(),0,limit,true);
        Candidate chosen=beam.get(0); List<ProductVariant> best=chosen.items(); long bestTotal=chosen.total();
        String introduction=semanticScores!=null ? "Mình xếp hạng các cấu hình có các cặp được duyệt theo độ gần ngữ nghĩa với nhu cầu. Điểm này không đo hiệu năng."
                : "Chưa có điểm ngữ nghĩa cho lần này; mình chọn theo giá trong số các cấu hình có cặp được duyệt.";
        StringJoiner pairChecks=new StringJoiner("\n", "\nĐối chiếu linh kiện:\n", "");
        for(int i=0;i<best.size();i++) for(int j=i+1;j<best.size();j++) {
            var check=store.assessPair(best.get(i).id,best.get(j).id);
            if(check.status()!=PairCompatibility.Status.NOT_APPLICABLE)
                pairChecks.add("Đã duyệt · "+best.get(i).category+" / "+best.get(j).category+": "+check.detail());
        }
        StringJoiner checks=new StringJoiner("\n", "\nKiểm tra bổ sung:\n", "");
        for(var check:BuildChecks.evaluate(best)) checks.add((check.status().equals("pass")?"Đạt":check.status().equals("fail")?"Không đạt":"Chưa xác minh")+" · "+check.name()+": "+check.detail());
        return new Answer(introduction+" Giá và tồn kho lấy từ dữ liệu hiện có; chưa gồm màn hình, hệ điều hành và phí phát sinh."+pairChecks+checks,best,bestTotal,limit,true);
    }
}
