# Phase 1 — Task 3: Database Integration + JPA Entities + Relationships
**Estimated Time:** 1.5 hours | **Status:** ✅ Completed

---

## 🎯 What You Learn
1. Spring Data JPA setup with PostgreSQL
2. JPA Entity annotations (@Entity, @Table, @Column, @Id, @GeneratedValue)
3. Entity relationships: @OneToMany, @ManyToOne
4. Cascade types and orphan removal
5. Fetch types: LAZY vs EAGER (defaults and overrides)
6. Bidirectional relationship management
7. @Enumerated — storing enums as strings
8. @CreationTimestamp / @UpdateTimestamp — auto auditing
9. HikariCP connection pool configuration
10. ddl-auto options and production recommendations
11. @Transactional basics
12. open-in-view: false — why this is critical

---

## 🧠 Core Concepts

### JPA Stack

```
Your Code (Entity classes)
    ↓
Spring Data JPA  (Repository abstraction)
    ↓
JPA Spec         (interface)
    ↓
Hibernate        (ORM implementation)
    ↓
JDBC             (low-level DB access)
    ↓
PostgreSQL
```

### @GeneratedValue Strategies

| Strategy | Behavior | Use |
|---|---|---|
| `IDENTITY` | DB auto-increment | PostgreSQL/MySQL — most common |
| `SEQUENCE` | DB sequence object | PostgreSQL preferred (batching) |
| `AUTO` | JPA picks | Avoid — unpredictable |
| `TABLE` | Separate ID table | Avoid — very slow |
| `UUID` | Generates UUID | Distributed systems |

**IDENTITY vs SEQUENCE:**
- IDENTITY: INSERT must execute immediately to get the generated ID (can't batch inserts)
- SEQUENCE: IDs pre-allocated in batches (better performance for bulk inserts)

### Cascade Types

| Type | What it does |
|---|---|
| `ALL` | All operations propagate to children |
| `PERSIST` | Save child when parent is saved |
| `MERGE` | Update child when parent is updated |
| `REMOVE` | Delete child when parent is deleted |
| `REFRESH` | Reload child when parent is refreshed |
| `DETACH` | Detach child when parent is detached |

**CascadeType.REMOVE vs orphanRemoval:**
- `REMOVE`: Delete children when parent explicitly deleted
- `orphanRemoval = true`: Delete children when removed from parent's collection
  ```java
  order.getItems().remove(item); // item deleted from DB if orphanRemoval = true
  ```

### Fetch Types

| Relationship | Default | Recommendation |
|---|---|---|
| `@OneToMany` | LAZY | ✅ Keep LAZY |
| `@ManyToOne` | EAGER | ⚠️ Override to LAZY |
| `@ManyToMany` | LAZY | ✅ Keep LAZY |
| `@OneToOne` | EAGER | ⚠️ Override to LAZY |

**Why LAZY for @ManyToOne?** EAGER causes N+1 in reverse:
```java
List<OrderItem> items = itemRepo.findAll(); // 1 query
// EAGER @ManyToOne on Order → 1 query PER item for its order!
// 100 items = 101 queries!
```

### ddl-auto Options

| Value | Behavior | Environment |
|---|---|---|
| `create` | Drop + create schema (LOSES DATA) | Testing only |
| `create-drop` | Create on start, drop on stop | Testing only |
| `update` | Alter schema to match entities | Dev only |
| `validate` | Validate schema, fail if mismatch | Pre-prod |
| `none` | Do nothing | Production (use Flyway) |

**Production rule:** Use `none` + Flyway migrations (Task 48).

### Open-Session-In-View (OSIV)

**What it does:** Keeps Hibernate session open for entire HTTP request (including view rendering).

**Why it's bad:**
- Keeps DB connections open longer (pool exhaustion under load)
- Enables lazy loading in view layer → N+1 hidden in templates
- Tight coupling between presentation and persistence layers

**Always disable:**
```yaml
spring:
  jpa:
    open-in-view: false
```

### HikariCP Connection Pool

HikariCP is the fastest JDBC connection pool. Spring Boot uses it by default.

**Key settings:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20       # Max connections
      minimum-idle: 5             # Always-ready connections
      connection-timeout: 30000   # 30s wait for connection
      idle-timeout: 600000        # Remove idle after 10 min
      max-lifetime: 1800000       # Recycle connection after 30 min
      leak-detection-threshold: 60000  # Warn if held >60s (dev only)
```

**Sizing formula:** `connections = (core_count × 2) + effective_spindle_count`
- 4 CPU cores → ~10 connections
- 8 CPU cores → ~20 connections
- More is NOT better (context switching overhead)

---

## 🛠️ Implementation

### Step 1: Dependencies

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>  <!-- H2 for tests -->
</dependency>
```

### Step 2: Start PostgreSQL with Docker

```bash
docker run --name postgres-order-service \
  -e POSTGRES_USER=orderuser \
  -e POSTGRES_PASSWORD=orderpass \
  -e POSTGRES_DB=orderdb \
  -p 5432:5432 \
  -d postgres:15
```

### Step 3: application.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: orderuser
    password: orderpass
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 60000
      pool-name: OrderServiceHikariPool
  jpa:
    hibernate:
      ddl-auto: update   # Dev only — use 'none' + Flyway in prod
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 20
        order_inserts: true
        order_updates: true
    open-in-view: false  # ALWAYS false
```

### Step 4: OrderStatus Enum

```java
// model/OrderStatus.java
public enum OrderStatus {
    PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED
}
```

### Step 5: Order Entity

```java
// model/Order.java
@Entity
@Table(name = "orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "items")        // Avoid circular reference in toString
@EqualsAndHashCode(exclude = "items")
@NamedEntityGraph(
    name = "Order.withItems",
    attributeNodes = @NamedAttributeNode("items")
)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version                         // Optimistic locking (Task 5)
    private Long version;

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 100)
    private String customerEmail;

    @Enumerated(EnumType.STRING)     // Store as "PENDING", not "0"
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(
        mappedBy = "order",          // Field name in OrderItem
        cascade = CascadeType.ALL,   // Save/delete items with order
        orphanRemoval = true,        // Delete items removed from collection
        fetch = FetchType.LAZY       // Explicit — LAZY is default for @OneToMany
    )
    @BatchSize(size = 10)            // N+1 mitigation (Task 6)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "order_date", nullable = false, updatable = false)
    private LocalDateTime orderDate;

    @UpdateTimestamp
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    // Helper methods — maintain bidirectional relationship
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }

    public void calculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

### Step 6: OrderItem Entity

```java
// model/OrderItem.java
@Entity
@Table(name = "order_items")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = "order")         // Avoid circular reference
@EqualsAndHashCode(exclude = "order")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
        fetch = FetchType.LAZY,     // Override default EAGER!
        optional = false            // NOT NULL constraint
    )
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Transient                       // Not persisted — calculated
    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
```

### Step 7: Updated DTOs

```java
// dto/request/OrderItemRequest.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItemRequest {
    @NotBlank(message = "Product name is required")
    @Size(min = 2, max = 200)
    private String productName;

    @NotNull @Min(1) @Max(1000)
    private Integer quantity;

    @NotNull @DecimalMin("0.01") @DecimalMax("999999.99")
    private BigDecimal unitPrice;
}

// dto/request/OrderRequest.java — now uses items list
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderRequest {
    @NotBlank(message = "Customer name is required")
    @Size(min = 2, max = 100)
    private String customerName;

    @NotBlank @Email
    private String customerEmail;

    @Valid                           // Validate each item
    @NotEmpty(message = "Order must contain at least one item")
    @Size(max = 100)
    private List<OrderItemRequest> items = new ArrayList<>();
}

// dto/response/OrderItemResponse.java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItemResponse {
    private Long id;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
}

// dto/response/OrderResponse.java — now includes items
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {
    private Long id;
    private String orderNumber;
    private String customerName;
    private String customerEmail;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private LocalDateTime orderDate;
    private LocalDateTime lastUpdated;
    @Builder.Default
    private List<OrderItemResponse> items = new ArrayList<>();
}
```

### Step 8: Repository

```java
// repository/OrderRepository.java
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);
    List<Order> findByCustomerEmail(String email);
    List<Order> findByStatus(OrderStatus status);
    boolean existsByOrderNumber(String orderNumber);
    long countByStatus(OrderStatus status);

    // JOIN FETCH — loads order AND items in single query
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    // For paginated + items (two-query approach)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
    List<Order> findByIdInWithItems(@Param("ids") List<Long> ids);
}
```

### Step 9: Service with Transactions

```java
// service/impl/OrderServiceImpl.java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)   // Default: all methods read-only
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    @Override
    @Transactional                 // Override: this writes
    public OrderResponse createOrder(OrderRequest request) {
        Order order = Order.builder()
                .orderNumber("ORD-" + System.currentTimeMillis())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        for (OrderItemRequest ir : request.getItems()) {
            order.addItem(OrderItem.builder()
                    .productName(ir.getProductName())
                    .quantity(ir.getQuantity())
                    .unitPrice(ir.getUnitPrice())
                    .build());
        }
        order.calculateTotal();

        Order saved = orderRepository.save(order); // Items saved via cascade
        log.info("Order created: id={}, total={}", saved.getId(), saved.getTotalAmount());
        return mapToResponse(saved);
    }

    @Override
    public OrderResponse getOrderById(Long id) {
        return orderRepository.findByIdWithItems(id)   // JOIN FETCH — no N+1
                .map(this::mapToResponse)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrder(Long id, OrderRequest request) {
        Order existing = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        existing.setCustomerName(request.getCustomerName());
        existing.setCustomerEmail(request.getCustomerEmail());
        existing.getItems().clear();  // orphanRemoval deletes old items

        for (OrderItemRequest ir : request.getItems()) {
            existing.addItem(OrderItem.builder()
                    .productName(ir.getProductName())
                    .quantity(ir.getQuantity())
                    .unitPrice(ir.getUnitPrice())
                    .build());
        }
        existing.calculateTotal();

        return mapToResponse(orderRepository.save(existing));
    }

    @Override
    @Transactional
    public void deleteOrder(Long id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        orderRepository.delete(order); // Cascade deletes items
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .lineTotal(item.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .orderDate(order.getOrderDate())
                .lastUpdated(order.getLastUpdated())
                .items(itemResponses)
                .build();
    }
}
```

---

## 🧪 Test Commands

```bash
# Create order with items
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerName":"John Doe",
    "customerEmail":"john@example.com",
    "items":[
      {"productName":"MacBook Pro","quantity":1,"unitPrice":2999.00},
      {"productName":"Magic Mouse","quantity":2,"unitPrice":79.99}
    ]
  }'
# Expected 201 — total = 2999 + 159.98 = 3158.98

# Get with JOIN FETCH (single SQL)
curl http://localhost:8080/api/v1/orders/1
# Watch console — ONE query with LEFT JOIN

# Verify in DB
docker exec -it postgres-order-service psql -U orderuser -d orderdb
# \dt
# SELECT * FROM orders;
# SELECT * FROM order_items;
```

---

## 🎯 Interview Q&A

**Q: What is JPA and how does it differ from JDBC?**
JPA is an ORM specification that maps Java objects to database tables, abstracting SQL away. Hibernate is the most popular implementation. JDBC requires writing SQL manually; JPA generates it automatically while providing object-oriented querying (JPQL).

**Q: Explain @OneToMany and @ManyToOne.**
@OneToMany on the parent (Order has many Items), @ManyToOne on the child (Item belongs to one Order). The @ManyToOne side owns the relationship — it holds the foreign key column (order_id). `mappedBy` on @OneToMany points to the field in the child that owns the relationship.

**Q: What are cascade types? When would you use orphanRemoval?**
Cascades propagate operations from parent to children. CascadeType.ALL = all operations cascade. `orphanRemoval = true` deletes a child entity when it is removed from the parent's collection — useful for compositions (Order → Items) where items have no life outside their order.

**Q: Why override @ManyToOne to LAZY?**
Default is EAGER, which loads the parent entity for every child fetch. Loading 100 OrderItems eagerly loads 100 Order entities — N+1 queries. Override to LAZY and use JOIN FETCH only when you actually need the parent.

**Q: What is the N+1 problem?**
Fetching N entities and then issuing N separate queries to load each entity's relationship. 100 orders with LAZY items = 1 order query + 100 item queries = 101 total. Solved by JOIN FETCH or @BatchSize.

**Q: What does @Version do?**
Enables optimistic locking. JPA adds the version value to UPDATE WHERE clauses. If another transaction already updated the row, the version won't match and 0 rows are updated → OptimisticLockException.

**Q: What is @Transient?**
Marks a field that should NOT be persisted to the database. Used for calculated fields (like `getLineTotal()`) or temporary runtime values.

**Q: What's the difference between @CreationTimestamp and @UpdateTimestamp?**
`@CreationTimestamp`: Set once on INSERT, never updated (set `updatable = false`). `@UpdateTimestamp`: Updated on every UPDATE automatically by Hibernate. Both managed by Hibernate, no application code needed.

**Q: Why set open-in-view to false?**
OSIV keeps a Hibernate session open during the entire HTTP request lifecycle including view rendering. This holds DB connections longer (pool exhaustion), encourages lazy loading in wrong layers, and tightly couples presentation to persistence. Always disable in production.

**Q: How do you handle bidirectional relationships?**
Use helper methods (`addItem()`, `removeItem()`) that update BOTH sides. Without this, only one side is set — the entity graph is inconsistent in memory even if DB is correct.
