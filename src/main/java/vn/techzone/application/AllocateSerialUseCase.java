package vn.techzone.application;

import vn.techzone.application.port.InventoryPort;
import java.util.Objects;

/** Binds a scanned unit through the inventory boundary. */
public final class AllocateSerialUseCase {
    private final InventoryPort inventory;
    public AllocateSerialUseCase(InventoryPort inventory) { this.inventory=Objects.requireNonNull(inventory); }
    public String execute(String orderId,String variantId,String serial,String actor) {
        return inventory.allocateSerial(orderId,variantId,serial,actor);
    }
}
