package vn.techzone.application;

import vn.techzone.application.port.RankingPort;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import vn.techzone.domain.RecommendationList;
import java.time.Instant;
import java.util.*;

/** Coordinates candidate eligibility and contextual ranking. */
public final class RecommendUseCase {
    private final RankingPort ranking;
    public RecommendUseCase(RankingPort ranking) { this.ranking=Objects.requireNonNull(ranking); }
    public List<ProductVariant> eligible(String primaryId,Set<String> inCart) { return ranking.eligibleRecommendations(primaryId,inCart); }
    public RankingPort.Result execute(String primaryId,Set<String> inCart,int topK,Map<String,Double> scores,String model) {
        if(topK<0) throw new IllegalArgumentException("Top K cannot be negative");
        RankingPort.Result ranked=ranking.recommendWithEmbeddings(primaryId,inCart,topK,scores,model);
        RecommendationList list=new RecommendationList(ranked.items(),ranked.strategy(),Instant.now());
        List<ProductVariant> bounded=list.topK(topK);
        return new RankingPort.Result() {
            public List<ProductVariant> items(){return bounded;}
            public String strategy(){return ranked.strategy();}
            public double inferenceMs(){return ranked.inferenceMs();}
            public double totalMs(){return ranked.totalMs();}
        };
    }
}
