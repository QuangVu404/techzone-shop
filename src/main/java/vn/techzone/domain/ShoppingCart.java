package vn.techzone.domain;

import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;
import vn.techzone.domain.TechZoneModels.CartItem;

/** Cart aggregate operating on the existing persisted cart items. */
public final class ShoppingCart {
    private final Map<String, CartItem> items;

    public ShoppingCart(Map<String, CartItem> items) { this.items = Objects.requireNonNull(items); }

    public void addItem(String variantId, int quantity) {
        addItem(variantId,quantity,0);
    }

    public void addItem(String variantId, int quantity,long unitPriceSnapshot) {
        if (variantId == null || variantId.isBlank() || quantity <= 0)
            throw new IllegalArgumentException("Invalid cart item");
        CartItem existing = items.get(variantId);
        if (existing == null) items.put(variantId, new CartItem(variantId, quantity,unitPriceSnapshot));
        else existing.updateQuantity(Math.addExact(existing.quantity, quantity));
    }

    public long calculateSubtotal(ToLongFunction<String> priceForVariant) {
        long result = 0;
        for (CartItem item : items.values())
            result = Math.addExact(result, item.calculateLineTotal(item.effectiveSnapshot(priceForVariant.applyAsLong(item.variantId))));
        return result;
    }
}
