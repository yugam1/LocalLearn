# Phase 7 — Resilience (Tasks 31–34)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

## Circuit Breaker States
```
CLOSED → [failure rate > threshold] → OPEN → [wait] → HALF_OPEN → CLOSED
                                                                  → OPEN
```

## Resilience4j Config
```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      inventoryService:
        maxAttempts: 3
        waitDuration: 1s
        exponentialBackoffMultiplier: 2.0
  timelimiter:
    instances:
      inventoryService:
        timeoutDuration: 3s
  ratelimiter:
    instances:
      orderCreation:
        limitForPeriod: 100
        limitRefreshPeriod: 1s
        timeoutDuration: 0s
  bulkhead:
    instances:
      inventoryService:
        maxConcurrentCalls: 10
        maxWaitDuration: 0ms
```

## Usage
```java
@CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
@Retry(name = "inventoryService")
@TimeLimiter(name = "inventoryService")
public CompletableFuture<InventoryResponse> checkInventory(String sku) {
    return CompletableFuture.supplyAsync(() -> inventoryClient.check(sku));
}

public CompletableFuture<InventoryResponse> inventoryFallback(String sku, Exception ex) {
    log.warn("Inventory unavailable (fallback): sku={}", sku);
    return CompletableFuture.completedFuture(InventoryResponse.defaultUnavailable());
}

@RateLimiter(name = "orderCreation", fallbackMethod = "rateLimitFallback")
@Bulkhead(name = "inventoryService")
public OrderResponse createOrder(OrderRequest req) { ... }

public OrderResponse rateLimitFallback(OrderRequest req, RequestNotPermitted ex) {
    throw new TooManyRequestsException("Rate limit exceeded. Please try again.");
}
```

## Dependency
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

## Interview Q&A
- **Circuit breaker OPEN state?** Fails immediately without calling service. Returns fallback. Allows downstream to recover. Prevents cascading failures.
- **Retry vs Circuit Breaker?** Retry: try again on transient failure. CB: stop trying when sustained failures. Use both — retry on blip, CB on outage.
- **Bulkhead?** Limits concurrent calls. Prevents one slow service consuming all threads. Named after ship compartment isolation.
- **Exponential backoff?** Delay doubles each retry: 1s→2s→4s. Gives service time to recover. Add jitter to prevent retry storms.
- **Fallback strategies?** Cached data, default/degraded response, queue for later, feature disabled. Never misleading data.
