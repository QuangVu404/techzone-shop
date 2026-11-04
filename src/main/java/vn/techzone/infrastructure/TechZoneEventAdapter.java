package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.EventPort;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Infrastructure adapter for consent-aware discovery events. */
public final class TechZoneEventAdapter implements EventPort {
    private final TechZoneStore store;
    public TechZoneEventAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public void recordSearch(String q,String filters,List<ProductVariant> candidates,String strategy,double latencyMs) {
        store.recordSearch(q,filters,candidates,strategy,latencyMs);
    }
    @Override public void recordSearchClick(String variantId) { store.recordSearchClick(variantId); }
    @Override public void recordProductView(String variantId) { store.recordProductView(variantId); }
    @Override public void recordRecommendationClick(String primaryId,String accessoryId) { store.recordRecommendationClick(primaryId,accessoryId); }
}
