package vn.techzone.domain;

import java.util.Objects;

/** Symmetric variant pairing; approval must still be checked against current specifications. */
public record CompatibilityRule(String primaryVariantId,String accessoryVariantId,boolean approved) {
    public CompatibilityRule {
        Objects.requireNonNull(primaryVariantId);Objects.requireNonNull(accessoryVariantId);
        if(primaryVariantId.equals(accessoryVariantId))throw new IllegalArgumentException("Cặp sản phẩm không hợp lệ.");
    }
    public boolean matchesVariants(String left,String right) { return key(primaryVariantId,accessoryVariantId).equals(key(left,right)); }
    public static String key(String left,String right) {
        Objects.requireNonNull(left);Objects.requireNonNull(right);
        return left.compareTo(right)<=0?left+"|"+right:right+"|"+left;
    }
}
