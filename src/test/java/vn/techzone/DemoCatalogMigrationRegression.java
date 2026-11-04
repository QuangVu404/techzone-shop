package vn.techzone;

import java.nio.file.Files;
import java.nio.file.Path;
import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;
import vn.techzone.domain.TechZoneModels.Role;

public final class DemoCatalogMigrationRegression {
    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile(Path.of("target"), "catalog-migration-", ".sqlite");
        TechZoneStore original = new TechZoneStore();
        try {
            original.product("MB-B760-D4").specs.remove("m2Protocol");
            original.product("MB-B760-D4").specs.remove("source.m2Protocol");
            original.product("MB-B650").specs.put("m2Protocol","user-custom-value");
            original.setThreshold("GPU-4060", 19, "test");
            try (SqliteStore db = new SqliteStore(path, original)) {
                db.importRealCatalog();
                var first = db.read();
                TechZoneStore upgraded = SqliteStore.decode(first.bytes());
                try {
                    if (upgraded.products().size() != 220 || upgraded.balance("GPU-4060").reorderThreshold != 19
                            || upgraded.products().stream().filter(p -> p.id.startsWith("PCP-")).count() != 200)
                        throw new AssertionError("Migration failed to preserve or add data");
                } finally { upgraded.closeRepository(); }
                db.importRealCatalog();
                if (db.read().revision() != first.revision()) throw new AssertionError("Migration ran twice");
                db.importVerifiedManufacturerPair();
                db.importVerifiedRyzen7600Pairs();
                db.importVerifiedStorageFacts();
                var verifiedState = db.read();
                TechZoneStore verified = SqliteStore.decode(verifiedState.bytes());
                try {
                    if(!"PCIe NVMe".equals(verified.product("MB-B760-D4").spec("m2Protocol"))
                            || !"user-custom-value".equals(verified.product("MB-B650").spec("m2Protocol")))
                        throw new AssertionError("Storage fact migration overwrote user data or omitted the source fact");
                    if (!verified.compatible("CPU-7800X3D", "PCP-LwNxFT")
                            || verified.eligibleRecommendations("CPU-7800X3D", java.util.Set.of()).stream().noneMatch(p -> p.id.equals("PCP-LwNxFT")))
                        throw new AssertionError("Sourced pair was not available to recommendations");
                    if (!"AM5".equals(verified.product("PCP-yXmmP6").spec("socket"))
                            || !verified.compatible("PCP-yXmmP6", "PCP-LwNxFT")
                            || !verified.compatible("PCP-yXmmP6", "MB-B650"))
                        throw new AssertionError("The sourced Ryzen 7600 combinations were not imported");
                    var cold=verified.recommendWithEmbeddings("PCP-yXmmP6",java.util.Set.of(),2,
                            java.util.Map.of("PCP-LwNxFT",1.0,"MB-B650",0.1),"test-embeddings");
                    if (!cold.strategy().contains("test-embeddings") || cold.items().isEmpty()
                            || !cold.items().get(0).id.equals("PCP-LwNxFT"))
                        throw new AssertionError("A new product did not use available semantic scores");
                    verified.flagCompatibility("CPU-7800X3D", "PCP-LwNxFT", "Kiểm tra lại", Role.SUPPORT, "test");
                    var review = verified.compatibilityReviews().stream().filter(r -> "Chờ rà soát".equals(r.status)
                            && "PCP-LwNxFT".equals(r.accessoryVariantId)).findFirst().orElseThrow();
                    if (!verified.reviewCompatibility(review.id,true,Role.SUPPORT,"test","").startsWith("Nhập căn cứ")
                            || verified.compatible("CPU-7800X3D", "PCP-LwNxFT"))
                        throw new AssertionError("An unsubstantiated pair was approved");
                    if (!verified.reviewCompatibility(review.id,true,Role.WAREHOUSE,"test","MSI: AM5 Ryzen 7000").startsWith("Đã")
                            || !verified.compatible("CPU-7800X3D", "PCP-LwNxFT"))
                        throw new AssertionError("Verified review failed");
                    verified.flagCompatibility("CPU-7800X3D", "PCP-LwNxFT", "Tạm ẩn", Role.SUPPORT, "test");
                    db.save(verified, verifiedState.revision());
                } finally { verified.closeRepository(); }
                db.importVerifiedManufacturerPair();
                TechZoneStore blocked = SqliteStore.decode(db.read().bytes());
                try { if (blocked.compatible("CPU-7800X3D", "PCP-LwNxFT")) throw new AssertionError("A blocked pair was restored"); }
                finally { blocked.closeRepository(); }
            }
            try (SqliteStore reopened = new SqliteStore(path, original)) {
                TechZoneStore persisted = SqliteStore.decode(reopened.read().bytes());
                try { if (persisted.products().size() != 220) throw new AssertionError("Catalogue was not persisted"); }
                finally { persisted.closeRepository(); }
            }
            System.out.println("Real catalogue migration: 200 sourced items, existing stock preserved, restart and repeat passed");
        } finally { original.closeRepository(); Files.deleteIfExists(path); }
    }
}
