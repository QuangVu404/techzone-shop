package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.net.URI;
import java.util.*;

/** Checks only explicitly sourced dimensions and connector counts; missing data is unknown. */
public final class PhysicalCompatibility {
    public record Check(String name, String status, String detail) { }
    private PhysicalCompatibility() { }
    private static ProductVariant find(List<ProductVariant> items,String category) {
        return items.stream().filter(p->p.category.equalsIgnoreCase(category)).findFirst().orElse(null);
    }
    private static String value(ProductVariant p,String key) {
        if(p==null) return "";
        String source=p.spec("source."+key), value=p.spec(key);
        try { URI uri=URI.create(source); if(!"https".equals(uri.getScheme()) || uri.getHost()==null) return ""; }
        catch(RuntimeException ex) { return ""; }
        return value==null?"":value.trim();
    }
    private static Integer number(ProductVariant p,String key) {
        try { String s=value(p,key); int n=Integer.parseInt(s); return n>=0 && n<10000?n:null; }
        catch(RuntimeException ex) { return null; }
    }
    private static Check dimension(String label,ProductVariant item,String itemKey,ProductVariant enclosure,String limitKey) {
        Integer actual=number(item,itemKey), limit=number(enclosure,limitKey);
        if(actual==null || limit==null) return new Check(label,"unknown","Thiếu thông số có nguồn cho cả hai linh kiện hoặc chưa chọn vỏ.");
        return new Check(label,actual<=limit?"pass":"fail",actual+" mm / giới hạn "+limit+" mm; cần xét thêm quạt, radiator và vị trí lắp.");
    }
    private static Check connector(String label,ProductVariant board,String requiredKey,ProductVariant psu,String availableKey) {
        Integer needed=number(board,requiredKey), available=number(psu,availableKey);
        if(needed==null || available==null) return new Check(label,"unknown","Chưa có số lượng đầu nối cùng loại được xác minh.");
        return new Check(label,available>=needed?"pass":"fail","Cần "+needed+", nguồn có "+available+"; phải đối chiếu chuẩn chân và cáp đi kèm.");
    }
    public static List<Check> evaluate(List<ProductVariant> items) {
        ProductVariant board=find(items,"Mainboard"), gpu=find(items,"GPU"), cooler=find(items,"Tản nhiệt"), psu=find(items,"PSU"), cpu=find(items,"CPU");
        ProductVariant chassis=find(items,"Thùng máy"); if(chassis==null) chassis=find(items,"Case"); if(chassis==null) chassis=find(items,"Vỏ máy");
        List<Check> out=new ArrayList<>();
        String boardForm=value(board,"boardFormFactor"), supported=value(chassis,"supportedBoardFormFactors");
        if(boardForm.isEmpty() || supported.isEmpty()) out.add(new Check("Mainboard / vỏ","unknown","Thiếu vỏ hoặc chuẩn mainboard và danh sách vỏ hỗ trợ có nguồn."));
        else out.add(new Check("Mainboard / vỏ",Arrays.stream(supported.split(",")).map(String::trim).anyMatch(boardForm::equalsIgnoreCase)?"pass":"fail","Mainboard "+boardForm+"; vỏ hỗ trợ "+supported+"."));
        String psuForm=value(psu,"psuFormFactor"), psuSupported=value(chassis,"supportedPsuFormFactors");
        if(psuForm.isEmpty() || psuSupported.isEmpty()) out.add(new Check("Nguồn / vỏ","unknown","Thiếu chuẩn nguồn hoặc danh sách chuẩn vỏ hỗ trợ có nguồn."));
        else out.add(new Check("Nguồn / vỏ",Arrays.stream(psuSupported.split(",")).map(String::trim).anyMatch(psuForm::equalsIgnoreCase)?"pass":"fail","Nguồn "+psuForm+"; vỏ hỗ trợ "+psuSupported+"."));
        out.add(dimension("Chiều dài GPU",gpu,"gpuLengthMm",chassis,"maxGpuLengthMm"));
        out.add(dimension("Chiều cao tản nhiệt",cooler,"coolerHeightMm",chassis,"maxCoolerHeightMm"));
        out.add(dimension("Chiều dài nguồn",psu,"psuLengthMm",chassis,"maxPsuLengthMm"));
        out.add(connector("Cáp CPU EPS",board,"requiredEpsConnectors",psu,"epsConnectors"));
        out.add(connector("Cáp GPU PCIe",gpu,"requiredPcieConnectors",psu,"pcieConnectors"));
        String bios=board==null||cpu==null?"":value(board,"biosSupport."+cpu.id);
        out.add(new Check("BIOS", "unknown",bios.isEmpty()?"Chưa có danh sách hỗ trợ CPU và phiên bản BIOS thực tế được xác minh.":"Nhà sản xuất yêu cầu "+bios+"; cần đối chiếu phiên bản BIOS thực tế và phiên bản bo mạch."));
        return List.copyOf(out);
    }
    /** Checks only relationships for which both product categories are selected. */
    public static List<Check> applicable(List<ProductVariant> items) {
        Set<String> categories=new HashSet<>(); for(ProductVariant p:items) categories.add(p.category);
        boolean hasCase=categories.contains("Thùng máy")||categories.contains("Case")||categories.contains("Vỏ máy");
        return evaluate(items).stream().filter(check->switch(check.name()) {
            case "Mainboard / vỏ" -> hasCase && categories.contains("Mainboard");
            case "Nguồn / vỏ", "Chiều dài nguồn" -> hasCase && categories.contains("PSU");
            case "Chiều dài GPU" -> hasCase && categories.contains("GPU");
            case "Chiều cao tản nhiệt" -> hasCase && categories.contains("Tản nhiệt");
            case "Cáp CPU EPS" -> categories.contains("Mainboard") && categories.contains("PSU");
            case "Cáp GPU PCIe" -> categories.contains("GPU") && categories.contains("PSU");
            case "BIOS" -> categories.contains("CPU") && categories.contains("Mainboard");
            default -> false;
        }).toList();
    }
}
