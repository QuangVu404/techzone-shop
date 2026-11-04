package vn.techzone.application.port;

import vn.techzone.domain.TechZoneModels.Role;
import vn.techzone.domain.TechZoneModels.Warranty;

/** Warranty lookup and support workflow boundary. */
public interface WarrantyPort {
    Warranty warranty(String serial);
    String createStaffRma(String serial,String customerId,String problem,Role role);
    String requestStaffPaidRepair(String serial,String customerId,String problem,Role role,String actor);
    String reviewWarrantyVerification(String verificationId,Role role,String actor);
}
