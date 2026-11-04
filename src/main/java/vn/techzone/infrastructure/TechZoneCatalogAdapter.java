package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.CatalogPort;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Infrastructure adapter for catalogue search operations. */
public final class TechZoneCatalogAdapter implements CatalogPort {
    private final TechZoneStore store;
    public TechZoneCatalogAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public List<ProductVariant> search(String q,String category,String socket,String memory,long maxPrice,boolean semantic,Map<String,String> filters) {
        return store.search(q,category,socket,memory,maxPrice,semantic,filters);
    }
    @Override public List<ProductVariant> searchWithEmbeddings(String q,String category,String socket,String memory,long maxPrice,Map<String,String> filters,Map<String,Double> scores) {
        return store.searchWithEmbeddings(q,category,socket,memory,maxPrice,filters,scores);
    }
    @Override public String searchMode(boolean semantic) { return store.searchMode(semantic); }
}
