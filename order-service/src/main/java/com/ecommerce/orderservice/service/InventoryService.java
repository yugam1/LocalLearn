package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.model.OrderItem;

import java.util.List;

public interface InventoryService {

    /**
     * Reserve (decrement) stock for every item that matches a known product
     * by name, using a pessimistic write lock per row. Must only ever be
     * called from within an already-active transaction (Propagation.MANDATORY).
     */
    void reserveInventoryItems(List<OrderItem> items);

    /**
     * Allocate the single last unit of a product using SERIALIZABLE
     * isolation instead of an explicit lock — only one concurrent
     * transaction can succeed. See docs/phase1_task5.md, Isolation Levels.
     */
    void allocateLastUnit(String productName);
}
