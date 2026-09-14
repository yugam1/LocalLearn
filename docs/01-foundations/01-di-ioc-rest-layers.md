# Project Setup + REST API Basics
**✅ Completed · plan: Phase 1, Task 1** — DI/IoC, layered architecture, RESTful design, DTOs

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Spring is an **object factory that owns your objects**. You never
`new` a collaborator — you declare it in your constructor, and the IoC container
builds the whole graph and hands dependencies in. That inversion (container calls
you, not you calling the container) is what makes everything swappable and testable.

```
        ┌─────────── IoC CONTAINER ───────────┐
        │  scans packages → finds stereotypes  │
        │  builds beans → injects constructors │
        └──────────────────┬───────────────────┘
                           ▼
   Controller ──▶ Service ──▶ Repository ──▶ DB
   (HTTP only)   (business)   (data access)
```

**Five rules you must never get wrong:**
1. **Constructor injection, always** — `final` fields, explicit deps, mockable without Spring, no NPE.
2. **Controllers are thin.** Business logic lives in the service — a controller only speaks HTTP.
3. **Entities never cross the API boundary.** DTOs in, DTOs out — the API contract is yours, not the schema's.
4. **URLs name resources, not actions.** `POST /orders`, never `/createOrder`. Verb = HTTP method.
5. **`BigDecimal` for money.** `double` is binary floating point: `0.1 + 0.2 ≠ 0.3`.

---

## 🔮 PREDICT FIRST

*Answer in your head BEFORE opening. Wrong predictions stick better than read answers.*

<details>
<summary><b>P1.</b> You add a second class implementing <code>OrderService</code>, also annotated <code>@Service</code>. What happens when the app starts?</summary>

**Startup fails** with `NoUniqueBeanDefinitionException` — the container finds two
candidates for one constructor parameter and refuses to guess. Fixes: `@Primary`
on the default one, `@Qualifier("beanName")` at the injection point, or inject
`List<OrderService>` if you actually want all of them.
</details>

<details>
<summary><b>P2.</b> <code>System.out.println(0.1 + 0.2);</code> — what prints, and what does that mean for an order-total column?</summary>

`0.30000000000000004`. Binary floating point cannot represent most decimal
fractions exactly, and the error **compounds** across additions and multiplications.
For money: `BigDecimal` with explicit scale/rounding, never `double`/`float`.
</details>

<details>
<summary><b>P3.</b> A controller uses field injection (<code>@Autowired private OrderService svc;</code>). In a plain JUnit test you do <code>new OrderController()</code> and call a method. What happens, and why does constructor injection prevent it?</summary>

`NullPointerException` — nobody injected the field; only Spring can populate it
via reflection. With constructor injection the class **cannot exist** without its
dependencies: `new OrderController(mockService)` and you're testing in pure Java.
That's the strongest practical argument for constructor injection.
</details>

---

## 📖 THE STORY

### 1. Inversion of Control — who calls `new`?

Without DI, `OrderController` does `new OrderServiceImpl()` — it is now welded to
one concrete class: can't swap it, can't mock it, and it must know how to build
the service's own dependencies, recursively. IoC flips it: classes *declare* what
they need; the container constructs the graph.

```java
@RestController
@RequiredArgsConstructor            // Lombok: constructor for final fields
public class OrderController {
    private final OrderService orderService;   // container injects
}
```

**Why constructor injection wins over field injection:** `final` → immutable and
thread-safe; dependencies visible in the signature; testable with plain `new`;
fails at startup (not at first use) if a dependency is missing. Spring auto-injects
a single constructor — no `@Autowired` needed.

### 2. Stereotypes — same bean, different meaning

`@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` +
`@ComponentScan` (scans **its own package downward** — put the main class at the root).

| Annotation | Layer | Extra behavior beyond `@Component` |
|---|---|---|
| `@RestController` | web | = `@Controller` + `@ResponseBody` (JSON, no views) |
| `@Service` | business | none — pure semantics |
| `@Repository` | data | translates persistence exceptions → `DataAccessException` |
| `@Configuration` | config | hosts `@Bean` factory methods |

### 3. REST design — resources, verbs, codes

```
POST   /api/v1/orders        create   → 201 Created
GET    /api/v1/orders        list     → 200
GET    /api/v1/orders/{id}   one      → 200 | 404
PUT    /api/v1/orders/{id}   replace  → 200        (idempotent)
PATCH  /api/v1/orders/{id}   partial  → 200        (not guaranteed idempotent)
DELETE /api/v1/orders/{id}   delete   → 204 No Content (idempotent)
```

**Idempotent** = same request N times, same end state. GET/PUT/DELETE yes; POST no
(each call creates another resource). This matters for retries: a client may safely
retry idempotent calls after a timeout.

Status codes to have on reflex: `400` malformed input · `404` no such resource ·
`409` conflict (optimistic lock) · `422` valid syntax, business rule says no · `500` our bug.

### 4. DTO vs entity — the boundary rule

The entity is the database's shape; the DTO is the API's promise. Exposing entities
leaks internal fields, invites over-posting (client sets `id`/`status`), causes
lazy-loading serialization surprises, and welds your API contract to your schema
so every migration becomes a breaking change. `OrderRequest` has no id/status/orderNumber —
those are system-generated; the client must not send them.

### 5. Layering

Controller (HTTP concerns) → Service interface + impl (business rules, mapping) →
storage. In Task 1 storage is `ConcurrentHashMap<Long, Order>` + `AtomicLong` id
generator — thread-safe because **Tomcat serves each request on a different thread**,
so shared mutable state is concurrent by default. (Why those exact classes misbehave
when done wrong: [02-concurrency/03](../02-concurrency/03-atomicity-races-cas.md).)

---

## 🛠 BUILD REFERENCE

<details>
<summary><b>Project setup + package layout</b></summary>

start.spring.io: Maven · Java 17/21 · Spring Boot 3.2.x · deps: Spring Web, DevTools, Lombok.

```
com.ecommerce.orderservice/
├── OrderServiceApplication.java
├── controller/OrderController.java
├── service/OrderService.java + impl/OrderServiceImpl.java
├── model/Order.java
└── dto/request/OrderRequest.java · response/OrderResponse.java
```
</details>

<details>
<summary><b>Domain model + DTOs</b></summary>

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Order {
    private Long id;
    private String orderNumber;          // "ORD-" + timestamp
    private String customerName, customerEmail, productName;
    private Integer quantity;
    private BigDecimal unitPrice, totalAmount;   // BigDecimal for money, always
    private String status;               // PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    private LocalDateTime orderDate, lastUpdated;
}
```

`OrderRequest` = the writable subset only (name, email, product, quantity, unitPrice).
`OrderResponse` = the full read view. Mapping lives in the service (`mapToResponse`).
</details>

<details>
<summary><b>Service (in-memory, thread-safe)</b></summary>

```java
@Service @Slf4j
public class OrderServiceImpl implements OrderService {
    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public OrderResponse createOrder(OrderRequest request) {
        BigDecimal total = request.getUnitPrice()
                .multiply(BigDecimal.valueOf(request.getQuantity()));
        Order order = Order.builder()
                .id(idGenerator.getAndIncrement())
                .orderNumber("ORD-" + System.currentTimeMillis())
                .customerName(request.getCustomerName())
                // ... remaining fields ...
                .totalAmount(total).status("PENDING")
                .orderDate(LocalDateTime.now()).build();
        orderStore.put(order.getId(), order);
        return mapToResponse(order);
    }
    // getOrderById / getAllOrders / updateOrder / deleteOrder + mapToResponse(...)
}
```
</details>

<details>
<summary><b>Controller + application.yml + curl smoke tests</b></summary>

```java
@RestController @RequestMapping("/api/v1/orders") @RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                             .body(orderService.createOrder(request));   // 201
    }
    @GetMapping("/{id}")  // 200
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }
    @DeleteMapping("/{id}")  // 204
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }
    // GET all, PUT — same pattern
}
```

```yaml
server: { port: 8080, shutdown: graceful }
spring.application.name: order-service
logging.level.com.ecommerce.orderservice: DEBUG
```

```bash
curl -X POST localhost:8080/api/v1/orders -H "Content-Type: application/json" \
  -d '{"customerName":"John Doe","customerEmail":"john@example.com","productName":"MacBook Pro","quantity":1,"unitPrice":2499.99}'
curl localhost:8080/api/v1/orders/1
curl -X DELETE localhost:8080/api/v1/orders/1
```
</details>

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, then open. Miss one → reread only that Story section.*

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> What is Dependency Injection, and what is "inverted" in Inversion of Control?</summary>

DI: objects receive dependencies from outside instead of constructing them.
Inverted: control over object creation and lifecycle moves from your code to the
container — the framework builds the graph and calls you. Payoff: loose coupling,
swappability, testability.
</details>

<details><summary><b>Q2.</b> Four concrete reasons constructor injection beats field injection.</summary>

(1) fields can be `final` → immutable, thread-safe; (2) dependencies explicit in
the signature; (3) unit-testable with plain `new` + mocks, no Spring context;
(4) missing deps fail at startup/construction, not as a runtime NPE.
</details>

<details><summary><b>Q3.</b> `@Controller` vs `@RestController`? `@Service` vs `@Repository`?</summary>

`@RestController` = `@Controller` + `@ResponseBody` — return values serialize to
JSON instead of resolving views. `@Service` is purely semantic; `@Repository`
additionally translates persistence exceptions into Spring's `DataAccessException`
hierarchy.
</details>

<details><summary><b>Q4.</b> PUT vs POST vs PATCH — including idempotency and why it matters.</summary>

POST creates (not idempotent — N calls, N resources). PUT replaces the whole
resource (idempotent). PATCH partially updates (not guaranteed idempotent).
Idempotency matters for safe retries after timeouts and for gateway/proxy behavior.
</details>

<details><summary><b>Q5.</b> Why must JPA entities never be returned from controllers?</summary>

Leaks internal/sensitive fields, allows over-posting on input, risks lazy-loading
serialization errors and circular references, and couples the public API contract
to the DB schema so schema changes break clients. DTOs keep the contract independent.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why is `new BigDecimal(0.1)` still wrong even though you used BigDecimal?</summary>

The `double` literal is already imprecise before BigDecimal sees it —
`new BigDecimal(0.1)` = 0.1000000000000000055511151231257827…
Use the String constructor `new BigDecimal("0.1")` or `BigDecimal.valueOf(0.1)`.
</details>

<details><summary><b>Q7.</b> Why do the in-memory store fields need `ConcurrentHashMap` and `AtomicLong` specifically?</summary>

Every HTTP request runs on a different Tomcat worker thread, so the map and the
counter are shared mutable state. Plain `HashMap` can corrupt or loop under
concurrent writes; `long id++` is a read-modify-write race that hands two requests
the same id. (Measured proof of both: concurrency lab D7 and the HashMap demo.)
</details>

<details><summary><b>Q8.</b> Where does component scanning start, and what bug does misplacing the main class cause?</summary>

From the package of the `@SpringBootApplication` class, downward. Put the main
class in a deeper package and sibling packages are silently never scanned —
beans "don't exist," injection fails with `NoSuchBeanDefinitionException`.
</details>

---

## 🃏 FLASHCARDS

```
Constructor injection — 4 wins	final/immutable · explicit deps · plain-Java testable · fail-fast at startup
@RestController = ?	@Controller + @ResponseBody (JSON, no view resolution)
@Repository's extra magic	Persistence exception → DataAccessException translation
Idempotent HTTP methods	GET, PUT, DELETE (POST no; PATCH not guaranteed)
Successful POST / DELETE status	201 Created / 204 No Content
400 vs 422	400 = malformed request; 422 = valid syntax, business rule rejects
Why BigDecimal for money	Binary FP can't represent decimals: 0.1+0.2=0.30000000000000004
Two @Service impls of one interface	NoUniqueBeanDefinitionException — fix with @Primary or @Qualifier
Component scan root	@SpringBootApplication's own package, downward
Where business logic lives	Service layer — controllers only translate HTTP
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| `AtomicLong` id generator | CAS retry loop under the hood | `02-concurrency/03` |
| DTO at the boundary | projections: query only what the contract needs | `01-foundations/04` |
| 404/409/422 mapping | the global handler that actually produces them | `01-foundations/02` |
| Constructor injection + interfaces | mocking seams in unit tests | `05-testing` |

---

## 🗓 REVISION LOG

Core Card + Gym only (~5 min). Miss ≥2 Tier-1 → reset to R1.

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleave: also do 2 other topics' gyms)
