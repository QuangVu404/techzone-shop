package vn.techzone.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import vn.techzone.domain.TechZoneModels.InventoryUnit;
import vn.techzone.domain.TechZoneModels.OrderLine;
import vn.techzone.domain.TechZoneModels.UnitStatus;

/** Allocation record joining an order item, a physical unit and its variant. */
public record UnitAllocation(String allocationId,String orderItemId,String unitId,String variantId,
                             String status,String allocatedBy,LocalDateTime scannedAt) implements Serializable {
    private static final long serialVersionUID=1L;
    public UnitAllocation {
        Objects.requireNonNull(allocationId);Objects.requireNonNull(orderItemId);Objects.requireNonNull(unitId);
        Objects.requireNonNull(variantId);Objects.requireNonNull(status);Objects.requireNonNull(scannedAt);
    }
    public boolean validateAllocation(OrderLine line,InventoryUnit unit,String orderId) {
        return line!=null && unit!=null && line.variantId.equals(variantId) && unit.serial.equals(unitId)
                && unit.verifyVariant(variantId) && unit.status==UnitStatus.RESERVED && orderId.equals(unit.orderId)
                && line.reservedQuantity>0 && orderItemId.equals(orderItemKey(orderId,variantId));
    }
    public static String orderItemKey(String orderId,String variantId) {
        return orderId.codePointCount(0,orderId.length())+":"+orderId+variantId;
    }
}
