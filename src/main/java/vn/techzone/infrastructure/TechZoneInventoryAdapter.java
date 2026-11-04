package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.InventoryPort;
import java.util.Objects;

/** Infrastructure adapter for available stock and serial allocation. */
public final class TechZoneInventoryAdapter implements InventoryPort {
    private final TechZoneStore store;
    public TechZoneInventoryAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public int available(String variantId) { return store.available(variantId); }
    @Override public String allocateSerial(String orderId,String variantId,String serial,String staffName) {
        return store.allocateSerial(orderId,variantId,serial,staffName);
    }
}
