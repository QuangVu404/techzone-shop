package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Candidate and ranking boundary; results remain subject to compatibility and stock. */
public interface RankingPort {
    interface Result {
        List<ProductVariant> items(); String strategy(); double inferenceMs(); double totalMs();
    }
    List<ProductVariant> eligibleRecommendations(String primaryId,Set<String> inCart);
    Result recommendWithEmbeddings(String primaryId,Set<String> inCart,int topK,Map<String,Double> embeddings,String model);
}
