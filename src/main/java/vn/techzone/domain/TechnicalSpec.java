package vn.techzone.domain;

import java.net.URI;

/** Variant-level attribute and optional recorded source; a URL alone is not verification. */
public record TechnicalSpec(String key,String value,String source,boolean manufacturerVerified) {
    public TechnicalSpec(String key,String value,String source) { this(key,value,source,false); }
    public TechnicalSpec {
        if(key==null || key.isBlank())throw new IllegalArgumentException("Tên thông số không hợp lệ.");
        value=value==null?"":value;source=source==null?"":source;
    }
    public boolean hasHttpsSource() {
        try { URI uri=URI.create(source);return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null; }
        catch(IllegalArgumentException ex) { return false; }
    }
    public boolean isVerified() { return manufacturerVerified && hasHttpsSource(); }
}
