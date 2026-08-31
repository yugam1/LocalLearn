package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.exception.InsufficientStockException;
import com.ecommerce.orderservice.exception.InvalidOrderException;
import com.ecommerce.orderservice.model.OrderItem;
import com.ecommerce.orderservice.model.Product;
import com.ecommerce.orderservice.repository.ProductRepository;
import com.ecommerce.orderservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    private final ProductRepository productRepository;

    /**
     * Propagation.MANDATORY: this method must only run inside a transaction
     * already started by the caller (e.g. OrderServiceImpl#createOrder or
     * OrderNestedTransactionHelper#createOrderWithSavepoint). If invoked
     * without an active transaction, Spring throws
     * IllegalTransactionStateException. Products not present in the catalog
     * are treated as untracked (not every OrderItem needs stock tracking in
     * this learning app).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveInventoryItems(List<OrderItem> items) {
        for (OrderItem item : items) {
            productRepository.findByNameForUpdate(item.getProductName()).ifPresent(product -> {
                if (product.getStock() < item.getQuantity()) {
                    throw new InsufficientStockException(
                            product.getName(), item.getQuantity(), product.getStock());
                }
                product.setStock(product.getStock() - item.getQuantity());
                log.debug("Reserved {} unit(s) of '{}', remaining stock={}",
                        item.getQuantity(), product.getName(), product.getStock());
            });
        }
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void allocateLastUnit(String productName) {
        Product product = productRepository.findByName(productName)
                .orElseThrow(() -> new InvalidOrderException("Unknown product: " + productName));
        if (product.getStock() <= 0) {
            throw new InsufficientStockException(productName, 1, product.getStock());
        }
        product.setStock(product.getStock() - 1);
        productRepository.save(product);
        log.info("Allocated last unit of '{}', remaining stock={}", productName, product.getStock());
    }
}
