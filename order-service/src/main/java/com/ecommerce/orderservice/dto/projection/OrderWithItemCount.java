package com.ecommerce.orderservice.dto.projection;

/**
 * Interface projection backed by a GROUP BY / COUNT query
 * (see OrderRepository#findOrderItemCounts).
 */
public interface OrderWithItemCount {
    Long getId();
    String getOrderNumber();
    Long getItemCount();
}
