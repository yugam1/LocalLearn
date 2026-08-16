package OrderSystem;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

enum ItemCategory {
    CHEAP, MODERATE, EXPENSIVE
}

class MenuItem {
    int id;
    String name;
    Double price;
    ItemCategory category;

    MenuItem(int id, String name, Double price) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.category = getCategory();
    }

    public ItemCategory getCategory() {
        if (this.price <= 10) {
            return ItemCategory.CHEAP;
        } else if (this.price > 10 && this.price <= 20) {
            return ItemCategory.MODERATE;
        } else {
            return ItemCategory.EXPENSIVE;
        }
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public int getId() {
        return this.id;
    }

    public void setPrice(Double price) {
        this.price = price;
        this.category = getCategory();
    }

    public Double getPrice() {
        return this.price;
    }
}

class DiscountManager {
    Map<ItemCategory, Double> discountRules;

    DiscountManager() {
        discountRules = new HashMap<>();
        discountRules.put(ItemCategory.CHEAP, 10.0);
        discountRules.put(ItemCategory.MODERATE, 20.0);
        discountRules.put(ItemCategory.EXPENSIVE, 30.0);
    }

    Double getPriceAfterDiscount(MenuItem item) {
        return item.getPrice() * (100 - discountRules.getOrDefault(item.getCategory(), 0.0)) / 100;
    }
}

interface IOrder {

    int addItem(MenuItem m);

    int removeItem(MenuItem m);

    Double getBill(DiscountManager m, Map<Integer, MenuItem> menu);
}

class Order implements IOrder {
    int orderId;
    Map<Integer, Integer> itemsOrdered;
    int userId;

    Order(int id, int userId) {
        this.orderId = id;
        itemsOrdered = new HashMap<>();
        this.userId = userId;

    }

    public int addItem(MenuItem m) {
        itemsOrdered.put(m.getId(), itemsOrdered.getOrDefault(m.getId(), 0) + 1);
        return itemsOrdered.get(m.getId());
    }

    public int removeItem(MenuItem m) {
        if (!itemsOrdered.containsKey(m.getId())) {
            return -1;
        }
        itemsOrdered.put(m.getId(), itemsOrdered.getOrDefault(m.getId(), 0) - 1);
        return itemsOrdered.get(m.getId());
    }

    public Double getBill(DiscountManager m, Map<Integer, MenuItem> menu) {
        Double total = 0.0;
        for (var row : itemsOrdered.entrySet()) {
            int menuId = row.getKey();
            int quantity = row.getValue();
            total += quantity * m.getPriceAfterDiscount(menu.get(menuId));
        }
        return total;
    }
}

interface IOrderSystem {
    List<MenuItem> getMenu();

    Boolean addItemToOrder(int userId, MenuItem MenuItem);

    Boolean removeItemFromOrder(int userId, MenuItem MenuItem);

    Double getBillAmount(int userId);
}

class OrderManagementSystem implements IOrderSystem {
    Map<Integer, MenuItem> menu;
    Map<Integer, Order> orderbyUsers;
    AtomicInteger orderId;
    DiscountManager dm;

    OrderManagementSystem(Map<Integer, MenuItem> menu) {
        this.menu = Collections.unmodifiableMap(menu);
        orderbyUsers = new ConcurrentHashMap<>();
        orderId = new AtomicInteger(0);
        dm = new DiscountManager();
    }

    public List<MenuItem> getMenu() {
        return new ArrayList<>(menu.values());
    }

    public Boolean addItemToOrder(int userId, MenuItem item) {
        if (!orderbyUsers.containsKey(userId)) {
            orderbyUsers.put(userId, new Order(orderId.getAndIncrement(), userId));
        }
        orderbyUsers.get(userId).addItem(item);
        return true;
    }

    public Boolean removeItemFromOrder(int userId, MenuItem item) {
        if (!orderbyUsers.containsKey(userId)) {
            return false;
            // no order for given user
        }
        if (orderbyUsers.get(userId).removeItem(item) == -1) {
            return false;
            // item not added in order
        }
        return true;
    }

    public Double getBillAmount(int userId) {
        if (!orderbyUsers.containsKey(userId)) {
            return -1.0;
            // no order for given user
        }
        return orderbyUsers.get(userId).getBill(dm, menu);
    }
}

/**
 * 
 * Pizza 40
 * 
 * Sandwich 30
 * 
 * After they are added, calculate the total amount from orders,
 * 
 * eg. The Price for Pizza-40 and 40>20, so the discount equals 30%. The
 * discounted price=40-((40*30)/100)=28. Similarly, the 30% discounted price of
 * Sandwich is 21.
 * 
 * Output:
 * 
 * Total Amount: 49
 * 
 * Expensive Category Discount: 21
 * 
 * Pizza (1 items)
 */
public class Try1 {
    public static void main(String[] args) {
        Map<Integer, MenuItem> menu = new HashMap<>();
        MenuItem pizza = new MenuItem(0, "PIZZA", 40.0);
        MenuItem sandwich = new MenuItem(1, "SANDWICH", 30.0);
        menu.put(pizza.getId(), pizza);
        menu.put(sandwich.getId(), sandwich);

        OrderManagementSystem oms = new OrderManagementSystem(menu);

        oms.getMenu();

        oms.addItemToOrder(0, sandwich); // 30
        // oms.addItemToOrder(0, sandwich); // 30

        oms.addItemToOrder(0, pizza);
        oms.addItemToOrder(0, pizza);
        oms.removeItemFromOrder(0, pizza);

        System.out.println(oms.getBillAmount(0));
    }
}
