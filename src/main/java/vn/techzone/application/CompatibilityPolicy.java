package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;

/** Compatibility-review policy shared by cart validation and recommendation eligibility. */
public final class CompatibilityPolicy {
    private CompatibilityPolicy() { }

    public static boolean needsReviewedRule(ProductVariant a, ProductVariant b) {
        if (a == null || b == null) return false;
        return pair(a, b, "CPU", "Mainboard")
                || pair(a, b, "Mainboard", "RAM")
                || pair(a, b, "GPU", "PSU")
                || pair(a, b, "CPU", "Tản nhiệt")
                || pair(a, b, "Mainboard", "SSD")
                || ("Điện thoại".equals(a.category) && isModelSpecificAccessory(b))
                || ("Điện thoại".equals(b.category) && isModelSpecificAccessory(a));
    }

    /** Cart combinations require review for any phone/accessory pair, including generic accessories. */
    public static boolean needsReviewedCartPair(ProductVariant a, ProductVariant b) {
        if (a == null || b == null) return false;
        boolean phoneAccessory = ("Điện thoại".equals(a.category) && "Phụ kiện".equals(b.category))
                || ("Điện thoại".equals(b.category) && "Phụ kiện".equals(a.category));
        return needsReviewedRule(a, b) || phoneAccessory;
    }

    private static boolean pair(ProductVariant a, ProductVariant b, String first, String second) {
        return (first.equals(a.category) && second.equals(b.category))
                || (second.equals(a.category) && first.equals(b.category));
    }

    private static boolean isModelSpecificAccessory(ProductVariant product) {
        if (!"Phụ kiện".equals(product.category)) return false;
        String name = TechZoneStore.normalize(product.name);
        return !product.spec("forModel").isBlank() || name.contains("op lung") || name.contains("case");
    }
}
