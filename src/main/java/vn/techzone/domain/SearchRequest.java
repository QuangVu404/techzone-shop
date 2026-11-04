package vn.techzone.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validated input for a catalogue search. */
public final class SearchRequest {
    public final String query,category,socket,memory;
    public final long maxPrice;
    public final Instant createdAt;
    private final Map<String,String> filters;

    public SearchRequest(String query,String category,String socket,String memory,long maxPrice,Map<String,String> filters) {
        this.query=clean(query);this.category=clean(category);this.socket=clean(socket);this.memory=clean(memory);
        this.maxPrice=maxPrice;this.filters=copy(filters);this.createdAt=Instant.now();validate();
    }
    public void validate() {
        if(maxPrice<0) throw new IllegalArgumentException("Maximum price cannot be negative");
        for(var filter:filters.entrySet()) if(filter.getKey().isBlank() || filter.getValue().isBlank())
            throw new IllegalArgumentException("Search filter keys and values are required");
    }
    public SearchRequest updateFilters(Map<String,String> next) { return new SearchRequest(query,category,socket,memory,maxPrice,next); }
    public Map<String,String> filters() { return Map.copyOf(filters); }
    private static String clean(String value) { return value==null?"":value.trim(); }
    private static Map<String,String> copy(Map<String,String> source) {
        Map<String,String> result=new LinkedHashMap<>();
        if(source!=null) source.forEach((k,v)->result.put(Objects.requireNonNull(k).trim(),Objects.requireNonNull(v).trim()));
        return result;
    }
}
