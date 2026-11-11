package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalogue query filtering and relevance ranking, independent from session and persistence state. */
public final class CatalogSearchService {
    @FunctionalInterface public interface SemanticRanker { Map<String,Double> rank(String query,List<ProductVariant> candidates); }

    /** External listings can be discovered without pretending they are internal stock. */
    public static boolean discoverable(ProductVariant product, int available) {
        return product.active && (available > 0 || "GEARVN".equals(product.spec("sourceProvider")));
    }

    public List<ProductVariant> search(Collection<ProductVariant> catalogue,ToIntFunction<String> available,
                                       String query,String category,String socket,String memory,long maxPrice,
                                       boolean semanticRanking,Map<String,String> filters,
                                       boolean semanticServiceAvailable,SemanticRanker ranker,
                                       Map<String,Double> embeddingScores) {
        String normalized=TechZoneStore.normalize(query);long queryMax=priceLimitFromQuery(normalized);
        long resolved=maxPrice>0&&queryMax>0?Math.min(maxPrice,queryMax):Math.max(maxPrice,queryMax);
        boolean requireSocket=socket!=null&&!socket.isBlank(),requireMemory=memory!=null&&!memory.isBlank();
        boolean ddr5=normalized.matches(".*\\bddr5\\b.*"),ddr4=normalized.matches(".*\\bddr4\\b.*");
        if((ddr5&&ddr4)||(normalized.contains("lga1700")&&normalized.contains("am5")))return List.of();
        String querySocket=normalized.contains("lga1700")?"LGA1700":normalized.contains("am5")?"AM5":"";
        String queryMemory=ddr5?"DDR5":ddr4?"DDR4":"";
        if(requireSocket&&!querySocket.isBlank()&&!socket.equalsIgnoreCase(querySocket))return List.of();
        if(requireMemory&&!queryMemory.isBlank()&&!memory.equalsIgnoreCase(queryMemory))return List.of();
        if(!querySocket.isBlank()){socket=querySocket;requireSocket=true;}
        if(!queryMemory.isBlank()){memory=queryMemory;requireMemory=true;}
        String requested=category==null||category.isBlank()?"Tất cả":category.trim();
        if("Tất cả".equals(requested)) {
            if(normalized.matches(".*\\b(tan nhiet|cooler|heatsink)\\b.*"))requested="Tản nhiệt";
            else if(normalized.matches(".*\\b(op lung|phone case)\\b.*"))requested="Phụ kiện";
            else if(normalized.matches(".*\\b(mainboard|motherboard|mobo|bo mach chu)\\b.*"))requested="Mainboard";
            else if(normalized.matches(".*\\b(nguon|psu|power supply)\\b.*"))requested="PSU";
            else if(normalized.matches(".*\\b(man hinh|monitor|screen)\\b.*"))requested="Màn hình";
            else if(normalized.matches(".*\\b(dien thoai|smartphone|phone)\\b.*"))requested="Điện thoại";
            else if(normalized.matches(".*\\b(ssd|nvme|o cung)\\b.*"))requested="SSD";
            else if(normalized.matches(".*\\b(gpu|card do hoa|graphics)\\b.*"))requested="GPU";
            else if(normalized.matches(".*\\b(ram|memory)\\b.*"))requested="RAM";
            else if(normalized.matches(".*\\b(cpu|processor|bo xu ly)\\b.*"))requested="CPU";
        }
        final String fSocket=Objects.toString(socket,""),fMemory=Objects.toString(memory,"");
        final boolean fRequireSocket=requireSocket,fRequireMemory=requireMemory;final String fCategory=requested;final long fPrice=resolved;
        List<ProductVariant> eligible=catalogue.stream().filter(p->discoverable(p,available.applyAsInt(p.id)))
                .filter(p->"Tất cả".equals(fCategory)||p.category.equalsIgnoreCase(fCategory))
                .filter(p->!fRequireSocket||p.spec("socket").equalsIgnoreCase(fSocket))
                .filter(p->!fRequireMemory||p.spec("memoryType").equalsIgnoreCase(fMemory))
                .filter(p->p.matchesFilters(filters)).filter(p->fPrice<=0||p.effectivePrice()<=fPrice).toList();
        if(normalized.isBlank())return eligible;
        if(embeddingScores!=null&&semanticServiceAvailable)return eligible.stream().filter(p->embeddingScores.getOrDefault(p.id,-1.0)>=0.25)
                .sorted(Comparator.comparingDouble((ProductVariant p)->embeddingScores.getOrDefault(p.id,-1.0)).reversed().thenComparing(p->p.id)).toList();
        boolean useSemantic=semanticRanking&&semanticServiceAvailable;
        Map<String,Double> aiScores=useSemantic&&ranker!=null?ranker.rank(normalized,eligible):Map.of();
        return eligible.stream().filter(p->useSemantic?aiScores.getOrDefault(p.id,0.0)>0.025:queryScore(p,normalized)>0)
                .sorted(useSemantic?Comparator.comparingDouble((ProductVariant p)->aiScores.getOrDefault(p.id,0.0)).reversed()
                        :Comparator.comparingInt((ProductVariant p)->queryScore(p,normalized)).reversed()).toList();
    }

    private long priceLimitFromQuery(String query) {
        Matcher m=Pattern.compile("(?:under|below|duoi|less than)\\s*([0-9][0-9.,]*)\\s*(trieu|million|m|nghin|thousand|k)?\\b").matcher(query);
        if(!m.find())return 0;String raw=m.group(1),unit=m.group(2);
        try {
            boolean decimal=unit!=null&&raw.matches("\\d+[.,]\\d{1,2}");
            long value=decimal?(long)Double.parseDouble(raw.replace(',','.')):Long.parseLong(raw.replaceAll("[.,]",""));
            if(unit==null)return value;
            return switch(unit){case "trieu","million","m"->decimal?(long)(Double.parseDouble(raw.replace(',','.'))*1_000_000L):value*1_000_000L;
                case "nghin","thousand","k"->decimal?(long)(Double.parseDouble(raw.replace(',','.'))*1_000L):value*1_000L;default->value;};
        } catch(NumberFormatException ex){return 0;}
    }
    private int queryScore(ProductVariant p,String query) {
        String searchable=TechZoneStore.normalize(p.name+" "+p.category+" "+p.brand+" "+p.description+" "+p.sku+" "+p.specs);
        String[] tokens=query.split("\\s+");int score=0;
        Set<String> structural=Set.of("mainboard","motherboard","mobo","cpu","processor","ram","memory","gpu","graphics","under","below","duoi","less","than","trieu","million","m","nghin","thousand","k");
        for(String token:tokens){if(token.length()<2||token.matches("(lga1700|am5|ddr[45])")||structural.contains(token))continue;
            if(searchable.contains(token))score+=p.name.toLowerCase(Locale.ROOT).contains(token)?5:2;
            else if(synonymMatches(token,p))score++;
            else if("intel".equals(token)&&"LGA1700".equals(p.spec("socket")))score+=2;
            else if("amd".equals(token)&&"AM5".equals(p.spec("socket")))score+=2;
        }
        boolean structured=Arrays.stream(tokens).allMatch(t->t.length()<2||t.matches("(lga1700|am5|ddr[45])")||structural.contains(t));
        return score==0&&structured?1:score;
    }
    private boolean synonymMatches(String token,ProductVariant p) {
        Map<String,String> synonyms=Map.ofEntries(Map.entry("game","gaming"),Map.entry("choi","gaming"),Map.entry("cooler","tan nhiet"),
                Map.entry("quiet","tan nhiet"),Map.entry("silent","tan nhiet"),Map.entry("compact","nho gon"),Map.entry("small","nho gon"),
                Map.entry("board","bo mach chu"),Map.entry("mainboard","bo mach chu"),Map.entry("motherboard","bo mach chu"),
                Map.entry("power","nguon"),Map.entry("psu","nguon"),Map.entry("monitor","man hinh"),Map.entry("screen","man hinh"),
                Map.entry("storage","ssd"),Map.entry("drive","ssd"),Map.entry("phone","dien thoai"),Map.entry("smartphone","dien thoai"),
                Map.entry("processor","cpu"),Map.entry("graphics","card do hoa"));
        String mapped=synonyms.get(token);return mapped!=null&&TechZoneStore.normalize(p.name+" "+p.category+" "+p.description).contains(mapped);
    }
}
