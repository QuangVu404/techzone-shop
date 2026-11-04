package vn.techzone.application.port;

/** Inventory and physical serial allocation boundary. */
public interface InventoryPort {
    int available(String variantId);
    String allocateSerial(String orderId,String variantId,String serial,String staffName);
}
