package com.ecommerce.orderservice.model;

/**
 * Lifecycle states of an {@link Order}.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
