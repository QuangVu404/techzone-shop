package vn.techzone.infrastructure;

import vn.techzone.application.TechZoneStore;
import vn.techzone.application.port.WarrantyPort;
import vn.techzone.domain.TechZoneModels.*;
import java.util.Objects;

/** Infrastructure adapter for warranty and repair workflows. */
public final class TechZoneWarrantyAdapter implements WarrantyPort {
    private final TechZoneStore store;
    public TechZoneWarrantyAdapter(TechZoneStore store) { this.store = Objects.requireNonNull(store); }
    @Override public Warranty warranty(String serial) { return store.warranty(serial); }
    @Override public String createStaffRma(String serial,String customerId,String problem,Role role) { return store.createStaffRma(serial,customerId,problem,role); }
    @Override public String requestStaffPaidRepair(String serial,String customerId,String problem,Role role,String actor) {
        return store.requestStaffPaidRepair(serial,customerId,problem,role,actor);
    }
    @Override public String reviewWarrantyVerification(String id,Role role,String actor) { return store.reviewWarrantyVerification(id,role,actor); }
}
