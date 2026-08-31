# Phase 2 — Task 14: Reactive Programming with Spring WebFlux
**Estimated Time:** 2 hours | **Status:** ⬜ Not Started

---

## 🎯 What You Learn
- Reactive programming model vs imperative (blocking)
- Mono<T> — 0 or 1 item reactive type
- Flux<T> — 0 to N items reactive type
- Non-blocking HTTP with WebFlux @RestController
- Reactive database access with R2DBC
- Backpressure — flow control in reactive streams
- WebClient — non-blocking HTTP client (replaces RestTemplate)
- Reactive Kafka consumers
- When to use reactive vs traditional blocking

---

## 🧠 Core Concepts

### Blocking vs Reactive
```
BLOCKING (Traditional):
Thread 1: Request → Wait for DB → Wait for network → Return response
Thread 2: Request → Wait for DB → Wait for network → Return response
Thread 3: ...
Each request holds a thread while waiting (idle threads!)

REACTIVE (WebFlux):
Thread 1: Request → DB call (async) → free! → handle another request
Thread 1: DB responds → continue processing → Return response
1-2 threads handle thousands of concurrent requests!
```

### Mono and Flux
```java
Mono<Order> = Publisher that emits 0 or 1 Order
Flux<Order> = Publisher that emits 0 to N Orders

// Like Promise (Node.js) but composable
// Like RxJS Observable

// Mono — single result
Mono<Order> findById(Long id);
// Flux — multiple results
Flux<Order> findAll();
```

### Operators (Composable Pipeline)
```java
// map — transform each element
Mono<OrderResponse> orderMono = orderMono.map(order -> mapToResponse(order));

// flatMap — async transform (returns Mono/Flux)
Mono<OrderResponse> result = orderRepository.findById(id)
    .flatMap(order -> inventoryService.checkStock(order))  // async!
    .flatMap(order -> kafkaService.publishEvent(order))
    .map(order -> mapToResponse(order));

// filter — keep only matching
Flux<Order> activeOrders = allOrders.filter(o -> o.getStatus() == ACTIVE);

// collectList — Flux → Mono<List>
Mono<List<Order>> listMono = orderFlux.collectList();

// switchIfEmpty — handle empty Mono
Mono<Order> order = orderRepository.findById(id)
    .switchIfEmpty(Mono.error(new OrderNotFoundException(id)));

// onErrorResume — fallback
Mono<Order> withFallback = orderMono
    .onErrorResume(e -> Mono.just(defaultOrder));

// zipWith — combine two Monos
Mono<Tuple2<Order, Customer>> combined = orderMono.zipWith(customerMono);

// doOnNext — side effect (logging)
Mono<Order> withLog = orderMono.doOnNext(o -> log.info("Order: {}", o.getId()));
```

### Backpressure
```java
// Consumer controls how fast producer emits
Flux<Order> orders = orderFlux
    .onBackpressureBuffer(100)    // Buffer up to 100 items
    .onBackpressureDrop()         // Drop if consumer can't keep up
    .onBackpressureLatest();      // Keep only latest

// In HTTP: WebFlux handles automatically
// Producer won't emit faster than consumer can receive (TCP backpressure)
```

### WebFlux Controller
```java
@RestController
@RequestMapping("/api/v1/reactive/orders")
@RequiredArgsConstructor
public class ReactiveOrderController {

    private final ReactiveOrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        return orderService.createOrder(request);
        // Non-blocking! Thread released immediately
    }

    @GetMapping("/{id}")
    public Mono<OrderResponse> getOrder(@PathVariable Long id) {
        return orderService.findById(id)
                .switchIfEmpty(Mono.error(new OrderNotFoundException(id)));
    }

    @GetMapping
    public Flux<OrderResponse> getAllOrders() {
        return orderService.findAll();
    }

    // Server-Sent Events (SSE) — real-time streaming!
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<OrderResponse> streamOrders() {
        return orderService.findAll()
                .delayElements(Duration.ofMillis(100));  // 10 orders/second
    }
}
```

### R2DBC — Reactive Database
```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>io.r2dbc</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
</dependency>
```

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/orderdb
    username: orderuser
    password: orderpass
```

```java
// Reactive repository
public interface ReactiveOrderRepository extends ReactiveCrudRepository<Order, Long> {
    Flux<Order> findByStatus(OrderStatus status);
    Mono<Order> findByOrderNumber(String orderNumber);
}

// Reactive service
@Service
@RequiredArgsConstructor
public class ReactiveOrderService {
    private final ReactiveOrderRepository orderRepository;

    public Mono<OrderResponse> findById(Long id) {
        return orderRepository.findById(id)
                .map(this::mapToResponse)
                .switchIfEmpty(Mono.error(new OrderNotFoundException(id)));
    }

    public Flux<OrderResponse> findAll() {
        return orderRepository.findAll()
                .map(this::mapToResponse);
    }
}
```

### WebClient — Non-Blocking HTTP
```java
// Create WebClient
WebClient client = WebClient.builder()
        .baseUrl("https://api.shipping.com")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .filter(ExchangeFilterFunction.ofRequestProcessor(req -> {
            log.info("Request: {} {}", req.method(), req.url());
            return Mono.just(req);
        }))
        .build();

// Make non-blocking call
Mono<ShippingRate> rate = client.get()
        .uri("/rates/{orderId}", orderId)
        .retrieve()
        .onStatus(HttpStatus::is4xxClientError, response ->
                Mono.error(new ShippingServiceException("Client error")))
        .bodyToMono(ShippingRate.class)
        .timeout(Duration.ofSeconds(5))
        .retryWhen(Retry.backoff(3, Duration.ofMillis(500)));

// Parallel calls with zip
Mono<Tuple2<ShippingRate, TaxRate>> combined = Mono.zip(
        shippingService.getRate(orderId),
        taxService.getRate(orderId)
);
```

### When to Use Reactive vs Traditional?
| Scenario | Use |
|----------|-----|
| High concurrency, many I/O-bound ops | **Reactive** |
| Server-Sent Events / streaming | **Reactive** |
| Low-latency, non-blocking calls | **Reactive** |
| CPU-bound processing | Traditional (threads better) |
| Complex business logic | Traditional (simpler debugging) |
| Existing JPA/Hibernate codebase | Traditional (JPA blocking, not reactive) |
| Simple CRUD | Traditional |
| Mixed sync + async operations | Traditional (@Async is simpler) |

**Rule of thumb:** If you can solve your problem with @Async + CompletableFuture, use that. Reactive is most beneficial when you need to handle thousands of concurrent long-lived connections (SSE, WebSocket) or complex async composition.

---

## 🛠️ Implementation

### application.yml — R2DBC Profile
```yaml
spring:
  profiles: reactive
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/orderdb
    username: orderuser
    password: orderpass
    pool:
      max-size: 20
      initial-size: 5
```

### Reactive Order Entity (for R2DBC — no @Entity annotations!)
```java
@Table("orders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ReactiveOrder {
    @Id
    private Long id;
    private String orderNumber;
    private String customerName;
    private String customerEmail;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime orderDate;
}
```

### Reactive Service
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ReactiveOrderServiceImpl implements ReactiveOrderService {
    private final ReactiveOrderRepository orderRepository;

    public Mono<OrderResponse> createOrder(OrderRequest request) {
        ReactiveOrder order = ReactiveOrder.builder()
                .orderNumber("ORD-" + System.currentTimeMillis())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status("PENDING")
                .totalAmount(calculateTotal(request))
                .orderDate(LocalDateTime.now())
                .build();

        return orderRepository.save(order)
                .map(this::mapToResponse)
                .doOnSuccess(r -> log.info("Order created reactively: id={}", r.getId()))
                .doOnError(e -> log.error("Failed to create order", e));
    }

    public Flux<OrderResponse> findAll() {
        return orderRepository.findAll()
                .map(this::mapToResponse)
                .doOnComplete(() -> log.info("All orders emitted"));
    }
}
```

---

## 🧪 Testing

```bash
# Test reactive endpoint
curl -X POST http://localhost:8080/api/v1/reactive/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"Reactive User","customerEmail":"rx@example.com","items":[...]}'

# Test SSE streaming
curl -N http://localhost:8080/api/v1/reactive/orders/stream
# data:{"id":1,"orderNumber":"ORD-123",...}
# data:{"id":2,"orderNumber":"ORD-456",...}
# Streams continuously!

# Load test — WebFlux handles 1000+ concurrent connections with 2 threads
# Traditional: needs 1000+ threads
```

---

## ✅ Completion Checklist
- [ ] spring-boot-starter-webflux or spring-boot-starter-data-r2dbc dependency
- [ ] ReactiveOrderRepository extends ReactiveCrudRepository
- [ ] ReactiveOrderService with Mono/Flux return types
- [ ] ReactiveOrderController with @RestController (non-blocking)
- [ ] SSE streaming endpoint (produces = TEXT_EVENT_STREAM_VALUE)
- [ ] WebClient configured for external service calls
- [ ] Operators: map, flatMap, switchIfEmpty, onErrorResume used
- [ ] Timeout + retry on WebClient
- [ ] GET /reactive/orders returns Flux
- [ ] GET /reactive/orders/stream SSE working

---

## 💬 Interview Q&A

**Q: What is reactive programming and how does it differ from traditional blocking?**
A: Traditional: each request blocks a thread while waiting for I/O (DB, network). With 1000 concurrent requests, you need 1000 threads. Reactive: non-blocking — threads released during I/O waits, resume when response arrives. 2-4 threads can handle thousands of concurrent requests. Uses Reactor types: Mono (0-1 items) and Flux (0-N items).

**Q: What is backpressure in reactive streams?**
A: Mechanism for the consumer to signal to the producer how fast it can process items. Prevents fast producers from overwhelming slow consumers. Unlike unbuffered reactive streams, consumer controls pull rate. Operators: onBackpressureBuffer, onBackpressureDrop, onBackpressureLatest.

**Q: When should you NOT use WebFlux?**
A: When using JPA/Hibernate (blocking). When team unfamiliar with reactive (steep learning curve). When business logic is complex (debugging stack traces in reactive is painful). CPU-bound operations (no benefit — still uses a thread). Simple apps where @Async is sufficient.

**Q: Mono.map vs Mono.flatMap?**
A: map — synchronous transformation (one item → one item, no async). flatMap — asynchronous transformation where the mapping function returns a Mono/Flux. Use flatMap for chaining async operations (DB call → another service call). map for simple type transformations.

**Q: What is WebClient and why replace RestTemplate?**
A: WebClient is Spring's non-blocking, reactive HTTP client. RestTemplate is synchronous (blocks thread until response). In WebFlux context, RestTemplate would block the event loop thread (catastrophic performance). WebClient returns Mono/Flux, integrates with reactive pipeline, supports backpressure, retry, timeout declaratively.

---

## 🔗 Next Task
**Task 15: Performance Optimization & Profiling** — JVM tuning, memory leak detection, query analysis, load testing with Gatling/JMeter.
