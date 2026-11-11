package vn.techzone;

import java.nio.file.*;
import vn.techzone.application.TechZoneStore;
import vn.techzone.backend.SqliteStore;

public final class GearVNDetailsRegression {
    public static void main(String[] args) throws Exception {
        Path dbPath=Files.createTempFile(Path.of("target"),"gearvn-details-",".sqlite");
        TechZoneStore initial=new TechZoneStore();
        try(SqliteStore db=new SqliteStore(dbPath,initial)) {
            db.importBundledGearVNCatalog();
            SqliteStore.State before=db.read();
            TechZoneStore local=SqliteStore.decode(before.bytes());
            String id, editedKey, misclassifiedId;
            try {
                var selected=local.products().stream().filter(p->p.id.startsWith("GVN-") && p.specs.keySet().stream().anyMatch(k->k.startsWith("Thông số · "))).findFirst().orElseThrow();
                id=selected.id;
                misclassifiedId=local.products().stream().filter(p->p.id.startsWith("GVN-") && p.name.contains("TRYX Panorama")).findFirst().orElseThrow().id;
                editedKey=selected.specs.keySet().stream().filter(k->k.startsWith("Thông số · ")).findFirst().orElseThrow();
                selected.specs.put(editedKey,"user-edited-value");
                local.product(id).specs.put("customNote","preserve");
                local.restock(id,3,"test");
                local.addToCart(id,1);
                db.save(local,before.revision());
            } finally { local.closeRepository(); }
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+dbPath.toAbsolutePath());
                var statement=connection.prepareStatement("UPDATE products SET category='RAM',brand_name='Khác' WHERE product_id=?")) {
                statement.setString(1,misclassifiedId);
                if(statement.executeUpdate()!=1) throw new AssertionError("Could not create a legacy category fixture");
            }
            db.importBundledGearVNDetails();
            long revision=db.read().revision();
            TechZoneStore after=SqliteStore.decode(db.read().bytes());
            try {
                if(after.available(id)!=3 || !"preserve".equals(after.product(id).spec("customNote")) ||
                        !after.cart().containsKey(id) || !"OK".equals(after.product(id).spec("sourceDetailStatus")) ||
                        !"user-edited-value".equals(after.product(id).spec(editedKey)))
                    throw new AssertionError("Detail import did not preserve local data");
                if(after.products().stream().filter(p->p.id.startsWith("GVN-") && "OK".equals(p.spec("sourceDetailStatus"))).count()!=200)
                    throw new AssertionError("Expected details for 200 products");
                if(after.products().stream().filter(p->p.id.startsWith("GVN-") && !p.spec("sourceSpecStatus").isBlank()).count()!=200)
                    throw new AssertionError("Expected a source specification status for each product");
                if(after.products().stream().filter(p->p.id.startsWith("GVN-") && p.specs.keySet().stream()
                        .anyMatch(key->key.startsWith("Thông số · ") || key.startsWith("Đặc điểm · "))).count()!=200)
                    throw new AssertionError("Expected source specifications or features for every product");
                if(after.products().stream().filter(p->p.id.startsWith("GVN-") && p.name.contains("TRYX Panorama"))
                        .anyMatch(p->!p.category.equals("Tản nhiệt") || !p.brand.equals("TRYX")))
                    throw new AssertionError("Cooler category and brand were not corrected");
                var board=after.products().stream().filter(p->p.id.startsWith("GVN-") && p.category.equals("Mainboard")
                        && p.spec("socket").equals("AM5") && p.spec("memoryType").equals("DDR5")).findFirst().orElseThrow();
                if(after.search("","Mainboard","AM5","DDR5",0,false).stream().noneMatch(p->p.id.equals(board.id)))
                    throw new AssertionError("Sourced attributes are not usable by technical filters");
                if(after.products().stream().noneMatch(p->p.name.contains("MWE GOLD 850W V3")
                        && p.spec("sourceQualityStatus").equals("REVIEW")
                        && p.spec("Thông số · Công suất theo hãng").equals("850 W")))
                    throw new AssertionError("Conflicting source data should be flagged with manufacturer evidence");
            } finally { after.closeRepository(); }
            db.importBundledGearVNDetails();
            if(db.read().revision()!=revision) throw new AssertionError("Detail migration must run once");
            System.out.println("GearVN detail migration: 200 records, local stock, cart and specs preserved");
        } finally { initial.closeRepository(); Files.deleteIfExists(dbPath); }
    }
}
