package vn.techzone;

import java.util.LinkedHashMap;
import vn.techzone.domain.ShoppingCart;
import vn.techzone.domain.TechZoneModels.*;

public final class DomainOperationsRegression {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid operation to fail"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) {
        var items = new LinkedHashMap<String,CartItem>();
        var cart = new ShoppingCart(items);
        cart.addItem("one",2); cart.addItem("one",1); cart.addItem("two",2);
        check(cart.calculateSubtotal(id -> id.equals("one") ? 100 : 250)==800,"cart total");
        rejects(() -> cart.addItem("one",0));
        rejects(() -> items.get("one").updateQuantity(-1));
        InventoryBalance balance=new InventoryBalance("one",5,0,0,1,"main");
        balance.reserve(3); balance.allocate(1); balance.release(2);
        check(balance.available==4 && balance.reserved==0 && balance.allocated==1,"stock transitions");
        rejects(() -> balance.reserve(5)); rejects(() -> balance.allocate(1)); rejects(() -> balance.release(1));
        check(balance.available==4 && balance.reserved==0 && balance.allocated==1,"invalid operations must not mutate stock");
        OrderLine line=new OrderLine("one",2,100); line.reserveQuantity(2);line.allocateQuantity(1);
        rejects(() -> line.allocateQuantity(2));
        check(line.reservedQuantity==1 && line.allocatedQuantity==1,"order allocation");
        System.out.println("Domain operations: cart totals, reservation, allocation and invalid transitions passed");
    }
}
