package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;
import java.util.regex.*;

/** Compares recorded facts only. MATCH is a partial check, not full compatibility certification. */
public final class PairCompatibility {
    public enum Status { MATCH, CONFLICT, UNKNOWN, NOT_APPLICABLE }
    public record Result(Status status,String detail) { }
    private PairCompatibility() { }
    private static ProductVariant of(ProductVariant a,ProductVariant b,String category) {
        return category.equals(a.category)?a:category.equals(b.category)?b:null;
    }
    private static String spec(ProductVariant p,String... keys) {
        if(p==null)return "";
        for(String key:keys) { var attribute=p.technicalSpec(key); if(!attribute.value().isBlank())return attribute.value().trim(); }
        return "";
    }
    private static String normalize(String v) { return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""); }
    private static String socket(String value) {
        String v=normalize(value).replaceFirst("^SOCKET","");
        return v.matches("AM[1-9]|LGA[0-9]{3,4}")?v:"";
    }
    private static String memoryType(String value) {
        String v=normalize(value);
        return v.matches("DDR[345]")?v:"";
    }
    private static Result compare(String label,String left,String right) {
        if(left.isBlank() || right.isBlank())return new Result(Status.UNKNOWN,"Thiếu thông số "+label+"; cần kiểm tra trước khi mua.");
        boolean equal=normalize(left).equals(normalize(right));
        return new Result(equal?Status.MATCH:Status.CONFLICT,label+": "+left+" / "+right+(equal?" khớp theo dữ liệu danh mục.":" không khớp."));
    }
    private static Integer watts(String text) {
        Matcher m=Pattern.compile("(?i)\\b(\\d{2,4})\\s*W\\b").matcher(text);
        if(!m.find())return null;
        int value=Integer.parseInt(m.group(1));return value>0 && value<=3000?value:null;
    }
    public static Result assess(ProductVariant a,ProductVariant b) {
        if(a==null || b==null)return new Result(Status.UNKNOWN,"Thiếu sản phẩm.");
        ProductVariant cpu=of(a,b,"CPU"), board=of(a,b,"Mainboard"), ram=of(a,b,"RAM"), gpu=of(a,b,"GPU"), psu=of(a,b,"PSU"), ssd=of(a,b,"SSD"), cooler=of(a,b,"Tản nhiệt");
        if(cpu!=null && board!=null) return compare("Socket",socket(spec(cpu,"socket","Socket")),socket(spec(board,"socket","Socket / CPU")));
        if(board!=null && ram!=null) {
            String memory=spec(ram,"memoryType","Memory Type");
            if(memory.isBlank()) { Matcher m=Pattern.compile("(?i)\\bDDR[345]\\b").matcher(spec(ram,"Speed")); if(m.find())memory=m.group(); }
            return compare("Loại bộ nhớ",memoryType(spec(board,"memoryType","Memory Type")),memoryType(memory));
        }
        if(cpu!=null && cooler!=null) {
            String socket=socket(spec(cpu,"socket","Socket")), support=spec(cooler,"socketSupport","Socket Support");
            if(socket.isBlank() || support.isBlank())return new Result(Status.UNKNOWN,"Thiếu socket CPU hoặc danh sách socket tản nhiệt.");
            String list=support.replaceAll("(?i)LGA\\s+(\\d{3,4})","LGA$1");
            Set<String> sockets=new HashSet<>();
            for(String token:list.split("[,;/\\s]+")) { String value=socket(token); if(!value.isEmpty())sockets.add(value); }
            if(sockets.isEmpty())return new Result(Status.UNKNOWN,"Không đọc được danh sách socket tản nhiệt.");
            boolean match=sockets.contains(socket);
            return new Result(match?Status.MATCH:Status.CONFLICT,"Socket CPU "+socket+"; tản nhiệt hỗ trợ "+support+".");
        }
        if(gpu!=null && psu!=null) {
            Integer required=watts(spec(gpu,"minPsu","Recommended PSU")), available=watts(spec(psu,"wattage","Wattage"));
            if(required==null || available==null)return new Result(Status.UNKNOWN,"Thiếu công suất nguồn khuyến nghị của GPU hoặc công suất nguồn.");
            return new Result(available>=required?Status.MATCH:Status.CONFLICT,"Nguồn "+available+" W; GPU yêu cầu tối thiểu "+required+" W theo danh mục; vẫn cần kiểm tra toàn bộ hệ thống và cáp.");
        }
        if(board!=null && ssd!=null) {
            String slots=spec(board,"m2","M.2 Slots"), iface=spec(ssd,"interface","Interface");
            if(slots.isBlank() || iface.isBlank())return new Result(Status.UNKNOWN,"Thiếu thông tin khe và giao thức SSD của mainboard.");
            String plain=java.text.Normalizer.normalize(slots,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
            if(Pattern.compile("\\b(khong|none|not)\\b|\\b0\\s*(khe|slots?)\\b").matcher(plain).find() && iface.toUpperCase(Locale.ROOT).contains("M.2"))
                return new Result(Status.CONFLICT,"Mainboard được ghi không có khe M.2.");
            if(!slots.toUpperCase(Locale.ROOT).contains("M.2") || !iface.toUpperCase(Locale.ROOT).contains("M.2"))
                return new Result(Status.UNKNOWN,"Cần đối chiếu loại khe và giao thức SSD thực tế.");
            String boardProtocol=spec(board,"m2Protocol"), boardSizes=spec(board,"m2Sizes"), ssdSize=spec(ssd,"formFactor","Form Factor");
            if(validSource(spec(board,"source.m2Protocol")) && validSource(spec(board,"source.m2Sizes")) && validSource(spec(ssd,"source.formFactor"))
                    && boardProtocol.equalsIgnoreCase("PCIe NVMe") && iface.toUpperCase(Locale.ROOT).contains("NVME")
                    && Arrays.stream(boardSizes.split("[,;\\s/]+")).anyMatch("2280"::equals)
                    && normalize(ssdSize).equals("M22280"))
                return new Result(Status.MATCH,"Khe M.2 PCIe/NVMe hỗ trợ kích thước 2280 theo dữ liệu hãng; cần kiểm tra các khe dùng chung.");
            return new Result(Status.UNKNOWN,"Đều ghi M.2; chưa xác minh giao thức, kích thước ổ và khe dùng chung.");
        }
        ProductVariant phone=of(a,b,"Điện thoại"), accessory=of(a,b,"Phụ kiện");
        if(phone!=null && accessory!=null) return compare("Mẫu thiết bị",spec(phone,"model"),spec(accessory,"forModel"));
        return new Result(Status.NOT_APPLICABLE,"Không có kiểm tra cặp cho loại sản phẩm này.");
    }
    private static boolean validSource(String url) {
        try { var uri=java.net.URI.create(url); return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null; }
        catch(RuntimeException e) { return false; }
    }
}
