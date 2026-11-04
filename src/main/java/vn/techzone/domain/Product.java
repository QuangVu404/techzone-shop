package vn.techzone.domain;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;

/** Product family and its independently priced, stocked variants. */
public final class Product {
    public final String id,name,category,brand,description;
    private final List<ProductVariant> variants=new ArrayList<>();
    public Product(String id,String name,String category,String brand,String description) {
        this.id=Objects.requireNonNull(id);this.name=Objects.requireNonNull(name);
        this.category=Objects.requireNonNull(category);this.brand=Objects.requireNonNull(brand);this.description=Objects.requireNonNull(description);
    }
    public void addVariant(ProductVariant variant) {
        Objects.requireNonNull(variant);
        if(!id.equals(variant.productId()) || !name.equals(variant.productName()) || !category.equals(variant.category)
                || !brand.equals(variant.brand) || !description.equals(variant.description))
            throw new IllegalArgumentException("Thông tin biến thể không thuộc sản phẩm này.");
        if(variants.stream().anyMatch(v->v.id.equals(variant.id) || v.sku.equals(variant.sku)))
            throw new IllegalArgumentException("Biến thể hoặc SKU bị trùng.");
        variants.add(variant);
    }
    public List<ProductVariant> variants() { return List.copyOf(variants); }
    public boolean active() { return variants.stream().anyMatch(v->v.active); }
}
