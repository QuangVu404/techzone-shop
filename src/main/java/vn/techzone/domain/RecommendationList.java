package vn.techzone.domain;

import java.time.Instant;
import java.util.List;
import vn.techzone.domain.TechZoneModels.ProductVariant;

/** Ordered, bounded recommendation result returned by a ranking strategy. */
public record RecommendationList(List<ProductVariant> items,String strategy,Instant generatedAt) {
    public RecommendationList {
        items=List.copyOf(items);strategy=strategy==null?"":strategy;generatedAt=generatedAt==null?Instant.now():generatedAt;
    }
    public List<ProductVariant> topK(int k) {
        if(k<0) throw new IllegalArgumentException("Top K cannot be negative");
        return items.subList(0,Math.min(k,items.size()));
    }
}
