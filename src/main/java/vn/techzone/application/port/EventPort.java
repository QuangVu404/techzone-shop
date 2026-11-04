package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.List;

/** Consent-aware discovery event recording. */
public interface EventPort {
    void recordSearch(String query,String filters,List<ProductVariant> candidates,String strategy,double latencyMs);
    void recordSearchClick(String variantId);
    void recordProductView(String variantId);
    void recordRecommendationClick(String primaryId,String accessoryId);
}
