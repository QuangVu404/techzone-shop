package vn.techzone.application;

import vn.techzone.application.port.WarrantyPort;
import vn.techzone.domain.TechZoneModels.Role;
import java.util.Objects;

/** Coordinates warranty lookup with staff support operations. */
public final class WarrantyUseCase {
    private final WarrantyPort warranty;
    public WarrantyUseCase(WarrantyPort warranty) { this.warranty=Objects.requireNonNull(warranty); }
    public String createRma(String serial,String problem,Role role) {
        var found=warranty.warranty(serial);
        return found==null?"Không tìm thấy bảo hành.":warranty.createStaffRma(found.serial,found.customerId,problem,role);
    }
    public String requestRepair(String serial,String problem,Role role,String actor) {
        var found=warranty.warranty(serial);
        return found==null?"Không tìm thấy bảo hành.":warranty.requestStaffPaidRepair(found.serial,found.customerId,problem,role,actor);
    }
    public String reviewVerification(String id,Role role,String actor) { return warranty.reviewWarrantyVerification(id,role,actor); }
}
