package vn.techzone.backend;

import vn.techzone.application.PhysicalCompatibility;
import vn.techzone.domain.TechZoneModels.ProductVariant;
import java.util.List;

/** Web-facing representation of shared physical compatibility checks. */
public final class BuildChecks {
    public record Check(String name,String status,String detail) { }
    private BuildChecks() { }
    public static List<Check> evaluate(List<ProductVariant> items) {
        return PhysicalCompatibility.evaluate(items).stream().map(c->new Check(c.name(),c.status(),c.detail())).toList();
    }
    public static List<Check> applicable(List<ProductVariant> items) {
        return PhysicalCompatibility.applicable(items).stream().map(c->new Check(c.name(),c.status(),c.detail())).toList();
    }
}
