# Phase 8 — Microservices Architecture (Tasks 35–41)
**Estimated Time:** 8 hours | **Status:** ⬜ Not Started

## Task 35: Service Discovery — Eureka

```xml
<!-- Server -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
<!-- Client -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication { }
```

```yaml
# Eureka server
server:
  port: 8761
eureka:
  client:
    registerWithEureka: false
    fetchRegistry: false

# Client (order-service)
eureka:
  client:
    serviceUrl:
      defaultZone: http://localhost:8761/eureka/
  instance:
    preferIpAddress: true
    instanceId: ${spring.application.name}:${server.port}
```

---

## Task 36: API Gateway — Spring Cloud Gateway

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: order-service
          uri: lb://order-service       # lb:// = load-balanced via Eureka
          predicates:
            - Path=/api/v1/orders/**
          filters:
            - AddRequestHeader=X-Service, gateway
            - name: CircuitBreaker
              args:
                name: orderService
                fallbackUri: forward:/fallback/orders

        - id: rate-limited
          uri: lb://order-service
          predicates:
            - Path=/api/v1/public/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
```

### Custom Global Filter (JWT Auth at Gateway)
```java
@Component @Slf4j
public class AuthGatewayFilter implements GlobalFilter, Ordered {
    private final JwtService jwtService;
    private static final List<String> PUBLIC = List.of("/api/v1/auth", "/actuator/health");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (PUBLIC.stream().anyMatch(path::startsWith)) return chain.filter(exchange);

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        try {
            String token = authHeader.substring(7);
            String username = jwtService.extractUsername(token);
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.header("X-User-ID", username)).build();
            return chain.filter(mutated);
        } catch (JwtException e) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override public int getOrder() { return -1; }
}
```

---

## Task 37: Config Server

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication { }
```

```yaml
# Config server
spring:
  cloud:
    config:
      server:
        git:
          uri: https://github.com/yourorg/config-repo
          default-label: main
          search-paths: '{application}'
server:
  port: 8888

# Client bootstrap (application.yml)
spring:
  config:
    import: optional:configserver:http://localhost:8888
  cloud:
    config:
      fail-fast: true
      retry:
        max-attempts: 6
        initial-interval: 1000
```

### Refresh Without Restart
```java
@RestController
@RefreshScope  // Re-reads config on /actuator/refresh
public class OrderController {
    @Value("${order.max-items-per-order:100}")
    private int maxItems;
}
```

```bash
# Trigger refresh (call on each instance, or use Spring Cloud Bus)
curl -X POST http://localhost:8080/actuator/refresh
```

---

## Task 38: Load Balancing — Spring Cloud LoadBalancer

```yaml
spring:
  cloud:
    loadbalancer:
      ribbon:
        enabled: false    # Use Spring Cloud LoadBalancer, not Ribbon
```

```java
@Bean
@LoadBalanced  // Enables lb:// URI resolution via Eureka
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}

// Usage — lb://service-name resolved to actual instance
WebClient client = webClientBuilder.build();
InventoryResponse res = client.get()
    .uri("lb://inventory-service/api/v1/inventory/{sku}", sku)
    .retrieve()
    .bodyToMono(InventoryResponse.class)
    .block();
```

---

## Task 39: Feign Client

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication { }

@FeignClient(
    name = "inventory-service",
    fallback = InventoryClientFallback.class,
    configuration = FeignClientConfig.class
)
public interface InventoryClient {
    @GetMapping("/api/v1/inventory/{sku}")
    InventoryResponse checkStock(@PathVariable String sku);

    @PutMapping("/api/v1/inventory/{sku}/reserve")
    void reserveStock(@PathVariable String sku, @RequestParam int quantity);
}

@Component
public class InventoryClientFallback implements InventoryClient {
    @Override
    public InventoryResponse checkStock(String sku) {
        log.warn("Inventory service unavailable: sku={}", sku);
        return InventoryResponse.unavailable();
    }
    @Override
    public void reserveStock(String sku, int quantity) {
        log.warn("Cannot reserve stock — inventory service down: sku={}", sku);
    }
}

@Configuration
public class FeignClientConfig {
    @Bean
    public RequestInterceptor correlationIdInterceptor() {
        return template -> {
            String correlationId = MDC.get("correlationId");
            if (correlationId != null) template.header("X-Correlation-ID", correlationId);
        };
    }
}
```

---

## Microservices Patterns

### Saga Pattern (Distributed Transactions)
```
Problem: Can't use @Transactional across services
Solution: Saga — sequence of local transactions with compensating actions

Choreography (event-driven):
  Order Service: create order → publish OrderCreated
  Inventory Service: consume OrderCreated → reserve stock → publish StockReserved
  Payment Service: consume StockReserved → charge → publish PaymentCompleted
  Order Service: consume PaymentCompleted → confirm order

Failure:
  Payment fails → publish PaymentFailed
  Inventory Service: consume PaymentFailed → release stock
  Order Service: consume StockReleased → cancel order
```

### Outbox Pattern (Reliable Event Publishing)
```
Problem: DB commit + Kafka publish are not atomic
  → DB commits, Kafka publish fails → no event published!
  → Kafka publishes, DB fails → phantom event!

Solution: Write event to outbox table in SAME transaction
  → Background job reads outbox → publishes to Kafka → marks published

@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    outboxRepo.save(OutboxEvent.builder()  // Same transaction!
        .aggregateId(order.getId())
        .eventType("ORDER_CREATED")
        .payload(toJson(buildEvent(order)))
        .build());
    return mapToResponse(order);
}

// Background job (Outbox publisher)
@Scheduled(fixedDelay = 1000)
@Transactional
public void publishOutboxEvents() {
    List<OutboxEvent> pending = outboxRepo.findByPublishedFalse();
    pending.forEach(event -> {
        kafkaTemplate.send(event.getEventType(), event.getPayload());
        event.setPublished(true);
        outboxRepo.save(event);
    });
}
```

---

## Interview Q&A

**Q: Service discovery vs hardcoded URLs?**
Discovery: services register with Eureka, clients resolve by name (`lb://order-service`) — automatic failover, scaling without config changes. Hardcoded: brittle, breaks on IP change, manual update on every scale event.

**Q: API Gateway vs load balancer?**
LB: Layer 4 (TCP), simple routing. Gateway: Layer 7 (HTTP), routing + auth + rate limiting + circuit breaker + request/response transformation + CORS. Gateway is the smart edge of your microservices.

**Q: Config server benefits?**
Centralise config for all services. Environment-specific values (dev/staging/prod). Change config without redeployment. @RefreshScope — live reload without restart. Encrypt secrets at rest. Audit trail of config changes.

**Q: Feign vs RestTemplate vs WebClient?**
RestTemplate: imperative, deprecated in Spring 6. WebClient: reactive, non-blocking. Feign: declarative (interface + annotations), integrates with Eureka/CB/Retry, less code. Feign preferred in microservices for synchronous calls.

**Q: Saga vs 2PC for distributed transactions?**
2PC (Two-Phase Commit): distributed lock, coordinator blocks all participants — slow, single point of failure. Saga: local transactions + compensating actions — no distributed lock, eventually consistent, more complex failure handling. Saga preferred in microservices.

**Q: What is the Outbox Pattern?**
Writes DB record + event to outbox table in same local transaction (atomic). Background job reads unpublished events → publishes to Kafka → marks published. Guarantees at-least-once event delivery even if Kafka is temporarily unavailable.

**Q: How to propagate correlation IDs across services?**
Feign interceptor adds `X-Correlation-ID` header to all outgoing calls. Receiving service's MDCFilter reads header → puts in MDC → all logs include it. Same for Kafka: include in event payload, consumer restores to MDC.
