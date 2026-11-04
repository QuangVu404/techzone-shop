package vn.techzone;

import vn.techzone.application.*;
import vn.techzone.application.port.*;
import vn.techzone.domain.TechZoneModels.*;
import java.util.*;

public final class ApplicationUseCaseRegression {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) {
        class Catalog implements CatalogPort {
            boolean semantic,embeddings;Map<String,String> received;
            public List<ProductVariant> search(String q,String c,String s,String m,long p,boolean ai,Map<String,String> f) { semantic=ai;received=f;return List.of(); }
            public List<ProductVariant> searchWithEmbeddings(String q,String c,String s,String m,long p,Map<String,String> f,Map<String,Double> scores) { embeddings=true;received=f;return List.of(); }
            public String searchMode(boolean requested) { return requested?"semantic":"keyword"; }
        }
        Catalog catalog=new Catalog();SearchUseCase search=new SearchUseCase(catalog);Map<String,String> filters=Map.of("speed","6000");
        var fallback=search.execute("RAM","RAM","","DDR5",0,filters,true,true,null,"");
        check(!catalog.semantic && filters.equals(catalog.received) && fallback.strategy().startsWith("Fallback"),"fallback must retain explicit filters");
        var ranked=search.execute("RAM","RAM","","DDR5",0,filters,true,false,Map.of("RAM-1",0.9),"BGE-M3");
        check(catalog.embeddings && ranked.strategy().contains("BGE-M3") && filters.equals(catalog.received),"semantic path must retain filters and model name");
        class Orders implements OrderPort {
            int calls;String key;
            public Result checkout(String address,String method,String promotion,PaymentVerdict verdict,String idempotency) {
                calls++;key=idempotency;
                return new Result() { public SalesOrder order(){return null;} public String message(){return "pending";} public boolean confirmed(){return false;} };
            }
        }
        Orders orders=new Orders();var result=new CheckoutUseCase(orders).execute("address","test","",PaymentVerdict.UNKNOWN,"key-1");
        check(orders.calls==1 && orders.key.equals("key-1") && !result.confirmed(),"checkout preserves outcome and idempotency key");
        class Inventory implements InventoryPort {
            String serial;public int available(String id){return 1;}
            public String allocateSerial(String o,String v,String s,String a){serial=s;return "ok";}
        }
        Inventory inventory=new Inventory();new AllocateSerialUseCase(inventory).execute("order","variant","serial-1","staff");
        check("serial-1".equals(inventory.serial),"allocation forwards the scanned serial");
        System.out.println("Application use cases: search fallback, checkout and serial delegation passed");
    }
}
