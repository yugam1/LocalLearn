package com.ecommerce.orderservice.dto.projection;

import com.ecommerce.orderservice.model.OrderStatus;

import java.math.BigDecimal;

/**
 * Spring Data interface projection — Spring generates the implementation at
 * runtime via a JDK proxy. Query only selects id/orderNumber/totalAmount/status
 * columns, no OrderItem join at all (see docs/phase1_task4.md, Projection Types).
 */
public interface OrderSummary {
    Long getId();
    String getOrderNumber();
    BigDecimal getTotalAmount();
    OrderStatus getStatus();
}
