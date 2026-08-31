package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.model.Order;

/**
 * Publishes order lifecycle events to Kafka. See docs/phase2_task10.md,
 * "KafkaProducerService".
 */
public interface KafkaProducerService {

    /** Publishes to {@code order.created}, keyed so same order stays ordered. */
    void publishOrderCreated(Order order);

    /** Publishes to {@code order.updated}. */
    void publishOrderUpdated(Order order);

    /** Publishes to {@code order.cancelled}. */
    void publishOrderCancelled(Order order, String reason);
}
