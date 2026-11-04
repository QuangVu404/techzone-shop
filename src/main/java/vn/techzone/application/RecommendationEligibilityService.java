package vn.techzone.application;

import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/** Applies hard catalogue, stock and reviewed-compatibility rules before any ranking model runs. */
public final class RecommendationEligibilityService {
    public List<ProductVariant> eligible(String primaryId, Set<String> selected,
                                         Collection<ProductVariant> catalogue,
                                         ToIntFunction<String> available,
                                         BiPredicate<String, String> approvedCompatible) {
        Map<String, ProductVariant> byId = new HashMap<>();
        for (ProductVariant product : catalogue) byId.put(product.id, product);
        ProductVariant primary = byId.get(primaryId);
        if (primary == null || !primary.active) return List.of();
        Set<String> cart = selected == null ? Set.of() : Set.copyOf(selected);
        return catalogue.stream()
                .filter(candidate -> !candidate.id.equals(primaryId) && !cart.contains(candidate.id))
                .filter(candidate -> candidate.active && available.applyAsInt(candidate.id) > 0)
                .filter(candidate -> CompatibilityPolicy.needsReviewedRule(primary, candidate))
                .filter(candidate -> approvedCompatible.test(primaryId, candidate.id))
                .filter(candidate -> compatibleWithSelected(candidate, cart, byId, approvedCompatible))
                .toList();
    }

    public boolean compatibleWithSelected(ProductVariant candidate, Set<String> selected,
                                          Collection<ProductVariant> catalogue,
                                          BiPredicate<String, String> approvedCompatible) {
        Map<String, ProductVariant> byId = new HashMap<>();
        for (ProductVariant product : catalogue) byId.put(product.id, product);
        return compatibleWithSelected(candidate, selected == null ? Set.of() : Set.copyOf(selected),
                byId, approvedCompatible);
    }

    private boolean compatibleWithSelected(ProductVariant candidate, Set<String> selected,
                                           Map<String, ProductVariant> byId,
                                           BiPredicate<String, String> approvedCompatible) {
        for (String selectedId : selected) {
            ProductVariant item = byId.get(selectedId);
            if (item == null) return false;
            if (CompatibilityPolicy.needsReviewedCartPair(item, candidate)
                    && !approvedCompatible.test(item.id, candidate.id)) return false;
        }
        return true;
    }
}
