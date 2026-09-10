package com.ecommerce.orderservice.support;

import com.ecommerce.orderservice.dto.request.OrderItemRequest;
import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.OrderStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Object-mother for tests. Keeping the builders here (rather than repeating
 * them per test class) means a change to a required field is a one-line fix
 * instead of a sweep across the suite.
 *
 * <p>Every factory returns a fully valid object; tests that need an invalid
 * one mutate a single field, so the assertion always names the one thing
 * under test.
 */
public final class TestData {

    public static final String CUSTOMER_NAME = "John Doe";
    public static final String CUSTOMER_EMAIL = "john@example.com";

    /** Laptop 1 x 2999.00 + Mouse 2 x 79.99 = 3158.98. */
    public static final BigDecimal EXPECTED_TOTAL = new BigDecimal("3158.98");

    private TestData() {
    }

    public static OrderRequest orderRequest() {
        return OrderRequest.builder()
                .customerName(CUSTOMER_NAME)
                .customerEmail(CUSTOMER_EMAIL)
                .items(Arrays.asList(
                        itemRequest("Laptop", 1, "2999.00"),
                        itemRequest("Mouse", 2, "79.99")))
                .build();
    }

    public static OrderRequest orderRequest(String customerName, String customerEmail) {
        OrderRequest request = orderRequest();
        request.setCustomerName(customerName);
        request.setCustomerEmail(customerEmail);
        return request;
    }

    public static OrderItemRequest itemRequest(String productName, int quantity, String unitPrice) {
        return OrderItemRequest.builder()
                .productName(productName)
                .quantity(quantity)
                .unitPrice(new BigDecimal(unitPrice))
                .build();
    }

    /** Transient order (no id) with the same two items as {@link #orderRequest()}. */
    public static Order order() {
        return order(null, "ORD-1001", OrderStatus.PENDING);
    }

    public static Order order(Long id, String orderNumber, OrderStatus status) {
        Order order = Order.builder()
                .id(id)
                .orderNumber(orderNumber)
                .customerName(CUSTOMER_NAME)
                .customerEmail(CUSTOMER_EMAIL)
                .status(status)
                .totalAmount(BigDecimal.ZERO)
                .build();
        order.addItem(item("Laptop", 1, "2999.00"));
        order.addItem(item("Mouse", 2, "79.99"));
        order.calculateTotal();
        return order;
    }

    /** Order with no items — total 0. Useful for isolating item-independent behaviour. */
    public static Order emptyOrder(Long id, String orderNumber, OrderStatus status) {
        return Order.builder()
                .id(id)
                .orderNumber(orderNumber)
                .customerName(CUSTOMER_NAME)
                .customerEmail(CUSTOMER_EMAIL)
                .status(status)
                .totalAmount(BigDecimal.ZERO)
                .items(new java.util.ArrayList<>())
                .build();
    }

    public static OrderItem item(String productName, int quantity, String unitPrice) {
        return OrderItem.builder()
                .productName(productName)
                .quantity(quantity)
                .unitPrice(new BigDecimal(unitPrice))
                .build();
    }

    public static List<OrderItemRequest> itemRequests() {
        return Arrays.asList(
                itemRequest("Laptop", 1, "2999.00"),
                itemRequest("Mouse", 2, "79.99"));
    }
}
