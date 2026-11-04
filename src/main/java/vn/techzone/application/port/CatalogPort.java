package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Catalogue search boundary; implementations enforce structured filters. */
public interface CatalogPort {
    List<ProductVariant> search(String query,String category,String socket,String memory,long maxPrice,boolean semantic,Map<String,String> filters);
    List<ProductVariant> searchWithEmbeddings(String query,String category,String socket,String memory,long maxPrice,Map<String,String> filters,Map<String,Double> scores);
    String searchMode(boolean semantic);
}
