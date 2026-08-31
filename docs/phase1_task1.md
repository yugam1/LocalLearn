# Phase 1 — Task 1: Project Setup + REST API Basics
**Estimated Time:** 1 hour | **Status:** ✅ Completed

---

## 🎯 What You Learn
1. Spring Boot project structure (industry-standard package organization)
2. Dependency Injection (DI) and Inversion of Control (IoC)
3. Constructor injection — why it is the best practice
4. Component scanning and stereotype annotations
5. RESTful API design principles (resource naming, HTTP methods, status codes)
6. Layered architecture: Controller → Service → Repository
7. DTO vs Domain Model separation
8. Lombok for boilerplate reduction
9. Spring MVC request processing pipeline
10. In-memory storage using thread-safe structures (ConcurrentHashMap, AtomicLong)

---

## 🧠 Core Concepts

### Dependency Injection & IoC

Spring's IoC container manages object creation and lifecycle. You declare dependencies; Spring injects them.

**Without DI (tight coupling — BAD):**
```java
public class OrderController {
    private OrderService orderService = new OrderServiceImpl(); // Hard-coded!
}
```

**With DI (loose coupling — GOOD):**
```java
@RestController
public class OrderController {
    private final OrderService orderService; // Spring injects this

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }
}
```

**Why constructor injection over field injection (`@Autowired` on field)?**
- Fields can be `final` → immutability → thread-safe
- Dependencies are explicit (you see them in the constructor)
- Works in unit tests without Spring context (just pass mocks)
- Prevents `NullPointerException` — dependencies set at construction time

### Stereotype Annotations

`@SpringBootApplication` includes `@ComponentScan` — Spring scans the package and all sub-packages for:

| Annotation | Layer | Notes |
|---|---|---|
| `@RestController` | Web / Presentation | = `@Controller` + `@ResponseBody` |
| `@Service` | Business Logic | Adds no extra Spring magic beyond `@Component` |
| `@Repository` | Data Access | Adds persistence exception translation |
| `@Component` | Generic | Base for all stereotypes |
| `@Configuration` | Config | For `@Bean` definitions |

### RESTful URL Design

```
✅ GOOD (resource-based):
POST   /api/v1/orders          → Create
GET    /api/v1/orders          → List all
GET    /api/v1/orders/{id}     → Get one
PUT    /api/v1/orders/{id}     → Replace entire resource
PATCH  /api/v1/orders/{id}     → Partial update
DELETE /api/v1/orders/{id}     → Delete

❌ BAD (action-based):
GET /api/v1/createOrder
GET /api/v1/getOrderById?id=1
POST /api/v1/deleteOrder
```

**Idempotency:**
- **GET, PUT, DELETE** = Idempotent (same request multiple times = same result)
- **POST** = Not idempotent (creates a new resource each time)
- **PATCH** = Not idempotent typically

### HTTP Status Codes

| Code | Meaning | Use Case |
|---|---|---|
| 200 | OK | Successful GET, PUT, PATCH |
| 201 | Created | Successful POST |
| 204 | No Content | Successful DELETE (no body) |
| 400 | Bad Request | Validation errors, malformed request |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Concurrent modification |
| 422 | Unprocessable Entity | Business rule violation |
| 500 | Internal Server Error | Unexpected server-side error |

### DTOs vs Domain Models

| Concern | Domain Model | DTO |
|---|---|---|
| Purpose | Internal representation | API contract |
| Exposure | Never expose to clients | What clients send/receive |
| Validation | JPA constraints | Bean validation annotations |
| Versioning | Database-driven | API-driven |

Never expose JPA entities directly in REST responses — entities may contain sensitive fields, circular references, and you lose control over your API contract.

### Layered Architecture

```
Controller  →  handles HTTP, delegates to service
Service     →  business logic, orchestration
Repository  →  database operations
Database
```

Business logic NEVER goes in controllers. Controllers are thin — only HTTP concerns.

---

## 🛠️ Implementation

### Step 1: Create Project at https://start.spring.io

```
Project:        Maven
Language:       Java
Spring Boot:    3.2.x
Java:           17 or 21
Group:          com.ecommerce
Artifact:       order-service
Packaging:      Jar
Dependencies:   Spring Web, Spring Boot DevTools, Lombok
```

### Step 2: Package Structure

```
src/main/java/com/ecommerce/orderservice/
├── OrderServiceApplication.java
├── controller/
│   └── OrderController.java
├── service/
│   ├── OrderService.java          (interface)
│   └── impl/
│       └── OrderServiceImpl.java
├── model/
│   └── Order.java
└── dto/
    ├── request/
    │   └── OrderRequest.java
    └── response/
        └── OrderResponse.java
```

### Step 3: Domain Model

```java
// model/Order.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private Long id;
    private String orderNumber;
    private String customerName;
    private String customerEmail;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;    // NEVER use double/float for money!
    private BigDecimal totalAmount;
    private String status;           // PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    private LocalDateTime orderDate;
    private LocalDateTime lastUpdated;
}
```

**Key:** `BigDecimal` for money (not `double` — floating point precision errors). `LocalDateTime` for dates (Java 8+ API, not old `Date`).

### Step 4: DTOs

```java
// dto/request/OrderRequest.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderRequest {
    private String customerName;
    private String customerEmail;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    // No id, orderNumber, status — system-generated
}

// dto/response/OrderResponse.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private String customerName;
    private String customerEmail;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private String status;
    private LocalDateTime orderDate;
    private LocalDateTime lastUpdated;
}
```

### Step 5: Service Interface

```java
// service/OrderService.java
public interface OrderService {
    OrderResponse createOrder(OrderRequest request);
    OrderResponse getOrderById(Long id);
    List<OrderResponse> getAllOrders();
    OrderResponse updateOrder(Long id, OrderRequest request);
    void deleteOrder(Long id);
}
```

**Why an interface?** Easy to mock in tests. Decouple controller from implementation.

### Step 6: Service Implementation

```java
// service/impl/OrderServiceImpl.java
@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    // Thread-safe in-memory storage (temporary — replaced by DB in Task 3)
    private final Map<Long, Order> orderStore = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public OrderResponse createOrder(OrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerName());

        BigDecimal totalAmount = request.getUnitPrice()
                .multiply(BigDecimal.valueOf(request.getQuantity()));

        Order order = Order.builder()
                .id(idGenerator.getAndIncrement())
                .orderNumber("ORD-" + System.currentTimeMillis())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .unitPrice(request.getUnitPrice())
                .totalAmount(totalAmount)
                .status("PENDING")
                .orderDate(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();

        orderStore.put(order.getId(), order);
        log.info("Order created: id={}, orderNumber={}", order.getId(), order.getOrderNumber());
        return mapToResponse(order);
    }

    @Override
    public OrderResponse getOrderById(Long id) {
        Order order = orderStore.get(id);
        if (order == null) {
            log.warn("Order not found: id={}", id);
            throw new RuntimeException("Order not found: " + id);
        }
        return mapToResponse(order);
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return orderStore.values().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public OrderResponse updateOrder(Long id, OrderRequest request) {
        Order existing = orderStore.get(id);
        if (existing == null) throw new RuntimeException("Order not found: " + id);

        BigDecimal totalAmount = request.getUnitPrice()
                .multiply(BigDecimal.valueOf(request.getQuantity()));

        existing.setCustomerName(request.getCustomerName());
        existing.setCustomerEmail(request.getCustomerEmail());
        existing.setProductName(request.getProductName());
        existing.setQuantity(request.getQuantity());
        existing.setUnitPrice(request.getUnitPrice());
        existing.setTotalAmount(totalAmount);
        existing.setLastUpdated(LocalDateTime.now());
        return mapToResponse(existing);
    }

    @Override
    public void deleteOrder(Long id) {
        if (orderStore.remove(id) == null) throw new RuntimeException("Order not found: " + id);
        log.info("Order deleted: id={}", id);
    }

    private OrderResponse mapToResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .productName(order.getProductName())
                .quantity(order.getQuantity())
                .unitPrice(order.getUnitPrice())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .orderDate(order.getOrderDate())
                .lastUpdated(order.getLastUpdated())
                .build();
    }
}
```

### Step 7: REST Controller

```java
// controller/OrderController.java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor   // Lombok: constructor for final fields → DI
@Slf4j
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);  // 201
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));  // 200
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> updateOrder(@PathVariable Long id,
                                                      @RequestBody OrderRequest request) {
        return ResponseEntity.ok(orderService.updateOrder(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();  // 204
    }
}
```

### Step 8: application.yml

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: order-service

logging:
  level:
    root: INFO
    com.ecommerce.orderservice: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

---

## 🧪 Test Commands

```bash
# Create
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John Doe","customerEmail":"john@example.com","productName":"MacBook Pro","quantity":1,"unitPrice":2499.99}'

# Get all
curl http://localhost:8080/api/v1/orders

# Get by ID
curl http://localhost:8080/api/v1/orders/1

# Update
curl -X PUT http://localhost:8080/api/v1/orders/1 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John Updated","customerEmail":"john@example.com","productName":"MacBook Pro M3","quantity":2,"unitPrice":2499.99}'

# Delete
curl -X DELETE http://localhost:8080/api/v1/orders/1
```

---

## 🎯 Interview Q&A

**Q: What is Dependency Injection?**
DI is a pattern where an object receives its dependencies from external sources rather than creating them. Spring's IoC container manages creation and lifecycle. Promotes loose coupling, testability, maintainability.

**Q: Why constructor injection over field injection?**
Constructor injection allows final fields (immutability), makes dependencies explicit, works without Spring in unit tests, and prevents NullPointerException since dependencies are guaranteed at construction time.

**Q: What's the difference between @Controller and @RestController?**
`@RestController` = `@Controller` + `@ResponseBody`. It automatically serializes return values to JSON. `@Controller` is for traditional MVC with Thymeleaf/JSP view rendering.

**Q: Why separate DTOs from domain models?**
DTOs control the API contract, hide sensitive/internal fields, enable API versioning independently from the database schema, and prevent over-posting (users can't set system-controlled fields like IDs).

**Q: What's the difference between PUT and POST?**
POST creates new resources (not idempotent — multiple calls create multiple resources). PUT replaces existing resources (idempotent — same request multiple times = same result).

**Q: What HTTP method is used for partial updates?**
PATCH. PUT replaces the entire resource. PATCH modifies only specified fields.

**Q: Why use ConcurrentHashMap over HashMap?**
ConcurrentHashMap is thread-safe for concurrent access. HashMap in multi-threaded code causes race conditions, data corruption, or infinite loops.

**Q: What does @RequiredArgsConstructor do?**
Lombok generates a constructor for all `final` fields. Spring uses this for constructor injection automatically when there is a single constructor.

**Q: What is @Slf4j?**
Lombok annotation that generates `private static final Logger log = LoggerFactory.getLogger(ClassName.class)`. Enables parameterized logging: `log.info("Order: {}", order)` — no string concatenation when log level is off.

**Q: Why use BigDecimal for money?**
`double` and `float` use binary floating point — imprecise for decimal fractions. 0.1 + 0.2 ≠ 0.3 in floating point. BigDecimal is exact for decimal arithmetic.
