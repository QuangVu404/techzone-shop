package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.RankingPort;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Infrastructure adapter for safe candidate retrieval and recommendation ranking. */
public final class TechZoneRankingAdapter implements RankingPort {
    private final TechZoneStore store;
    public TechZoneRankingAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public List<ProductVariant> eligibleRecommendations(String primaryId,Set<String> inCart) { return store.eligibleRecommendations(primaryId,inCart); }
    @Override public Result recommendWithEmbeddings(String primaryId,Set<String> inCart,int topK,Map<String,Double> scores,String model) {
        return store.recommendWithEmbeddings(primaryId,inCart,topK,scores,model);
    }
}
