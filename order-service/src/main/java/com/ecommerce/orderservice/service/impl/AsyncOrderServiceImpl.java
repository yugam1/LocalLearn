package com.ecommerce.orderservice.service.impl;

import com.ecommerce.orderservice.dto.request.OrderRequest;
import com.ecommerce.orderservice.dto.response.OrderResponse;
import com.ecommerce.orderservice.service.AsyncOrderService;
import com.ecommerce.orderservice.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * All the {@code @Async}/{@code CompletableFuture} patterns from
 * docs/phase2_task8.md, kept independent of {@link OrderServiceImpl}'s
 * order-creation flow: {@link #processOrderAsync} only ever *calls* the
 * existing, unmodified {@link OrderService#createOrder}.
 *
 * <p>{@link #self} is field-injected (lazily, to break the circular
 * reference) so that fan-out methods can invoke this bean's own
 * {@code @Async} methods <em>through the Spring proxy</em> — calling
 * {@code this.sendConfirmationEmailAsync(...)} directly would bypass the
 * proxy and run synchronously on the caller's thread. See
 * docs/phase2_task8.md, "@Async Rules".
 */
@Service
@Slf4j
public class AsyncOrderServiceImpl implements AsyncOrderService {

    private final OrderService orderService;
    private final Executor taskExecutor;

    @Autowired
    @Lazy
    private AsyncOrderService self;

    public AsyncOrderServiceImpl(OrderService orderService,
                                  @Qualifier("taskExecutor") Executor taskExecutor) {
        this.orderService = orderService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    @Async("taskExecutor")
    public CompletableFuture<OrderResponse> processOrderAsync(OrderRequest request) {
        log.info("Processing order async: thread={}", Thread.currentThread().getName());
        OrderResponse response = orderService.createOrder(request);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<BigDecimal> calculateDiscountedPricePipeline(String productName, BigDecimal basePrice) {
        // Sequential pipeline: each stage waits for the previous one, all on the default pool.
        return CompletableFuture
                .supplyAsync(() -> validate(productName, basePrice), taskExecutor)
                .thenApplyAsync(this::applyBulkDiscount, taskExecutor)
                .thenApplyAsync(this::applyTax, taskExecutor);
    }

    @Override
    @Async("emailExecutor")
    public CompletableFuture<Void> sendConfirmationEmailAsync(Long orderId) {
        log.info("Sending confirmation email: orderId={}, thread={}", orderId, Thread.currentThread().getName());
        simulateWork(500);
        log.info("Confirmation email sent: orderId={}", orderId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    @Async("inventoryExecutor")
    public CompletableFuture<Void> updateInventoryAsync(Long orderId) {
        log.info("Updating inventory: orderId={}, thread={}", orderId, Thread.currentThread().getName());
        simulateWork(300);
        log.info("Inventory updated: orderId={}", orderId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    @Async("analyticsExecutor")
    public CompletableFuture<Void> generateReportAsync(Long orderId) {
        log.info("Generating analytics report: orderId={}, thread={}", orderId, Thread.currentThread().getName());
        simulateWork(800);
        log.info("Analytics report generated: orderId={}", orderId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> completeOrderAsync(Long orderId) {
        // Parallel fan-out — email, inventory and report all run at once, each on its own pool.
        CompletableFuture<Void> email = self.sendConfirmationEmailAsync(orderId);
        CompletableFuture<Void> inventory = self.updateInventoryAsync(orderId);
        CompletableFuture<Void> report = self.generateReportAsync(orderId);

        return CompletableFuture.allOf(email, inventory, report)
                .thenRun(() -> log.info("Order {} post-processing complete (email + inventory + report)", orderId));
    }

    @Override
    @Async("taskExecutor")
    public CompletableFuture<String> checkWarehousePriceAsync(String warehouse, String productName) {
        long delayMillis = ThreadLocalRandom.current().nextInt(100, 600);
        simulateWork(delayMillis);
        String result = warehouse + ":" + productName + " responded in " + delayMillis + "ms";
        log.info("Warehouse price check complete: {}, thread={}", result, Thread.currentThread().getName());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<String> fetchFastestWarehousePrice(String productName) {
        // Race: whichever warehouse answers first wins. anyOf returns CompletableFuture<Object>.
        CompletableFuture<String> warehouse1 = self.checkWarehousePriceAsync("warehouse-1", productName);
        CompletableFuture<String> warehouse2 = self.checkWarehousePriceAsync("warehouse-2", productName);
        CompletableFuture<String> warehouse3 = self.checkWarehousePriceAsync("warehouse-3", productName);

        return CompletableFuture.anyOf(warehouse1, warehouse2, warehouse3)
                .thenApply(result -> (String) result);
    }

    @Override
    public CompletableFuture<BigDecimal> fetchPriceWithFallback(String productName) {
        return CompletableFuture
                .supplyAsync(() -> fetchExternalPrice(productName), taskExecutor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("External price lookup failed/timed out for '{}': {}", productName, ex.getMessage());
                    return BigDecimal.ZERO; // cached/fallback value — never propagate to the caller
                });
    }

    /** Simulates a flaky, sometimes-slow external pricing service. */
    private BigDecimal fetchExternalPrice(String productName) {
        simulateWork(ThreadLocalRandom.current().nextInt(200, 3000));
        if (ThreadLocalRandom.current().nextInt(10) < 3) {
            throw new IllegalStateException("External pricing service unavailable for " + productName);
        }
        return BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(10, 100)).setScale(2, RoundingMode.HALF_UP);
    }

    private String validate(String productName, BigDecimal basePrice) {
        if (productName == null || productName.isBlank() || basePrice == null || basePrice.signum() < 0) {
            throw new IllegalArgumentException("Invalid product/price for pipeline: " + productName);
        }
        log.info("[PIPELINE] validated '{}', thread={}", productName, Thread.currentThread().getName());
        return productName;
    }

    private BigDecimal applyBulkDiscount(String productName) {
        log.info("[PIPELINE] applying bulk discount for '{}', thread={}", productName, Thread.currentThread().getName());
        return BigDecimal.valueOf(90); // pretend base price after a flat 10% bulk discount
    }

    private BigDecimal applyTax(BigDecimal discountedPrice) {
        BigDecimal withTax = discountedPrice.multiply(BigDecimal.valueOf(1.08)).setScale(2, RoundingMode.HALF_UP);
        log.info("[PIPELINE] applied tax, final={}, thread={}", withTax, Thread.currentThread().getName());
        return withTax;
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
