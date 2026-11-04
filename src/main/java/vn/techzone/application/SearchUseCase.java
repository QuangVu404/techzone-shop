package vn.techzone.application;

import vn.techzone.application.port.CatalogPort;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import vn.techzone.domain.SearchRequest;
import java.util.*;

/** Selects the semantic or deterministic catalogue path without relaxing filters. */
public final class SearchUseCase {
    public record Result(List<ProductVariant> items,String strategy) { }
    private final CatalogPort catalog;
    public SearchUseCase(CatalogPort catalog) { this.catalog=Objects.requireNonNull(catalog); }
    public Result execute(String query,String category,String socket,String memory,long maxPrice,Map<String,String> filters,
                          boolean semanticRequested,boolean semanticUnavailable,Map<String,Double> scores,String model) {
        SearchRequest request=new SearchRequest(query,category,socket,memory,maxPrice,filters);
        boolean embeddings=scores!=null;
        List<ProductVariant> items=embeddings?catalog.searchWithEmbeddings(request.query,request.category,request.socket,request.memory,request.maxPrice,request.filters(),scores)
                :catalog.search(request.query,request.category,request.socket,request.memory,request.maxPrice,semanticRequested && !semanticUnavailable,request.filters());
        String strategy=embeddings?"AI ngữ nghĩa đa ngôn ngữ · "+model
                :semanticUnavailable?"Fallback từ khóa + thông số · mô hình chưa sẵn sàng":catalog.searchMode(semanticRequested);
        return new Result(items,strategy);
    }
}
