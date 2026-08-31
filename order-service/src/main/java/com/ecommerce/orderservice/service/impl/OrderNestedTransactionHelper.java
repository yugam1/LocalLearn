package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.model.Order;
import com.ecommerce.orderservice.repository.OrderRepository;
import com.ecommerce.orderservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal helper used exclusively by OrderServiceImpl#createOrdersBatch to
 * demonstrate Propagation.NESTED: each order in the batch gets its own
 * savepoint within the outer batch transaction. If one order fails
 * (e.g. insufficient stock), only its savepoint rolls back — siblings that
 * already succeeded are unaffected. See docs/phase1_task5.md, NESTED.
 *
 * NESTED requires the transaction manager to support JDBC savepoints; see
 * com.ecommerce.orderservice.config.TransactionConfig.
 */
@Service
@RequiredArgsConstructor
class OrderNestedTransactionHelper {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    @Transactional(propagation = Propagation.NESTED)
    public Order createOrderWithSavepoint(Order order) {
        inventoryService.reserveInventoryItems(order.getItems());
        return orderRepository.save(order);
    }
}
