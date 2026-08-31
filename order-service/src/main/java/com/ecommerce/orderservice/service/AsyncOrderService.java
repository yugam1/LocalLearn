package com.ecommerce.orderservice.service;

import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.OrderResponse;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * Demonstrates {@code @Async} + {@code CompletableFuture} patterns on top of
 * the thread pools configured in {@link com.ecommerce.orderservice.config.AsyncConfig}.
 * See docs/phase2_task8.md.
 */
public interface AsyncOrderService {

    /** Runs {@link OrderService#createOrder} on the default pool; caller gets a future back immediately. */
    CompletableFuture<OrderResponse> processOrderAsync(OrderRequest request);

    /** Sequential pipeline: each stage runs on the default pool, waiting for the previous stage. */
    CompletableFuture<BigDecimal> calculateDiscountedPricePipeline(String productName, BigDecimal basePrice);

    /** Dedicated email pool — won't starve order/inventory processing. */
    CompletableFuture<Void> sendConfirmationEmailAsync(Long orderId);

    /** Dedicated, high-priority inventory pool. */
    CompletableFuture<Void> updateInventoryAsync(Long orderId);

    /** Dedicated, low-priority analytics pool — tasks may be discarded under load. */
    CompletableFuture<Void> generateReportAsync(Long orderId);

    /** Parallel fan-out: email + inventory + report run concurrently on their own pools. */
    CompletableFuture<Void> completeOrderAsync(Long orderId);

    /** Single warehouse price check, invoked three times in parallel by {@link #fetchFastestWarehousePrice}. */
    CompletableFuture<String> checkWarehousePriceAsync(String warehouse, String productName);

    /** Race: queries three warehouses in parallel, returns whichever answers first. */
    CompletableFuture<String> fetchFastestWarehousePrice(String productName);

    /** Exception handling + timeout: falls back to a cached value if the lookup fails or is slow. */
    CompletableFuture<BigDecimal> fetchPriceWithFallback(String productName);
}
