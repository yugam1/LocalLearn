# Phase 1 — Task 6: JPA Performance — N+1, Batch Fetching, Connection Pooling
**Estimated Time:** 1 hour | **Status:** ✅ Completed

---

## The N+1 Problem

```java
List<Order> orders = orderRepository.findAll();  // 1 query
for (Order order : orders) {
    order.getItems().size(); // 1 query PER order!
}
// 100 orders = 101 queries | 1000 orders = 1001 queries | 10x+ slower!
```

```sql
-- What gets executed:
SELECT * FROM orders;                            -- 1
SELECT * FROM order_items WHERE order_id = 1;   -- N queries
SELECT * FROM order_items WHERE order_id = 2;
...
SELECT * FROM order_items WHERE order_id = 100;
```

**Detection:**
- Enable `show-sql: true` and `hibernate.generate_statistics: true`
- Look for `collectionFetchCount >> collectionLoadCount` in Hibernate statistics
- Watch for repeated similar queries in logs

---

## FetchType Defaults — Know These!

| Relationship | Default | Problem | Fix |
|---|---|---|---|
| `@OneToMany` | LAZY | Fine | Keep LAZY |
| `@ManyToOne` | **EAGER** | ⚠️ N+1 in reverse | **Override to LAZY** |
| `@ManyToMany` | LAZY | Fine | Keep LAZY |
| `@OneToOne` | **EAGER** | ⚠️ Extra query per entity | **Override to LAZY** |

```java
// ALWAYS override @ManyToOne!
@ManyToOne(fetch = FetchType.LAZY, optional = false)  // NOT the default EAGER
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

---

## Solutions to N+1 (in order of preference)

### 1. JOIN FETCH — Best for single entity or small lists
```java
// Single entity
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(@Param("id") Long id);

// All entities with items — ONE query
@Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items")
List<Order> findAllWithItems();

// SQL: SELECT o.*, oi.* FROM orders o LEFT JOIN order_items oi ON o.id = oi.order_id
```

**Limitation:** Cannot paginate directly (see paginated pattern below).

### 2. @BatchSize — Best when JOIN FETCH not possible
```java
@Entity
@BatchSize(size = 10)  // Batch the entity itself
public class Order {
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
    @BatchSize(size = 10)  // Batch the collection
    private List<OrderItem> items;
}
// 100 orders → 10 IN queries instead of 100
// SQL: SELECT * FROM order_items WHERE order_id IN (1,2,3,...,10)
```

Global configuration:
```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 10
```

### 3. DTO Projection — Best for list/summary views
```java
// No items fetched at all — fastest!
@Query("SELECT new com.ecommerce.dto.OrderSummaryDTO(o.id, o.orderNumber, o.totalAmount) FROM Order o")
List<OrderSummaryDTO> findAllSummaries();
// SQL: SELECT id, order_number, total_amount FROM orders
```

### 4. @EntityGraph — Declarative JOIN FETCH
```java
@EntityGraph(attributePaths = {"items"})
List<Order> findByStatus(OrderStatus status);
// Same SQL as JOIN FETCH but defined at method level, not query string
```

---

## Multiple Collection JOIN FETCH Issue

```java
// ❌ MultipleBagFetchException with two List collections
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.payments")
List<Order> findAllWithItemsAndPayments(); // THROWS EXCEPTION

// ✅ Solution 1: Change List to Set
private Set<OrderItem> items;
private Set<Payment> payments;
// Now JOIN FETCH on both works

// ✅ Solution 2: Two queries
List<Order> orders = findAllWithItems();
orders.forEach(o -> Hibernate.initialize(o.getPayments()));

// ✅ Solution 3: @BatchSize on second collection
@OneToMany @BatchSize(size = 10)
private List<Payment> payments;
```

---

## Paginated JOIN FETCH (Two-Query Pattern)

```java
// ❌ WRONG — HHH90003004 warning — memory pagination!
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items")
Page<Order> findAll(Pageable pageable); // Loads ALL, then slices in memory

// ✅ CORRECT — Two queries
@Query("SELECT o.id FROM Order o ORDER BY o.orderDate DESC")
Page<Long> findAllOrderIds(Pageable pageable); // Step 1: DB LIMIT/OFFSET on IDs

@Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
List<Order> findByIdInWithItems(@Param("ids") List<Long> ids); // Step 2: JOIN FETCH for those IDs

// In service:
Page<Long> idPage = orderRepository.findAllOrderIds(pageable);
List<Order> orders = orderRepository.findByIdInWithItems(idPage.getContent());
// Preserve page metadata: use PageImpl(orders, pageable, idPage.getTotalElements())
```

---

## Hibernate Statistics (Performance Monitoring)

```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true
```

```java
@Service
@RequiredArgsConstructor
public class StatisticsService {
    private final EntityManagerFactory emf;

    public Statistics getStatistics() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    public Map<String, Object> getSummary() {
        Statistics s = getStatistics();
        return Map.of(
            "queryExecutionCount", s.getQueryExecutionCount(),
            "entityLoadCount", s.getEntityLoadCount(),
            "collectionLoadCount", s.getCollectionLoadCount(),
            "collectionFetchCount", s.getCollectionFetchCount(),  // HIGH = N+1!
            "prepareStatementCount", s.getPrepareStatementCount(),
            "connectCount", s.getConnectCount()
        );
    }

    // If collectionFetchCount >> collectionLoadCount → N+1 problem!
    public boolean isPotentialN1() {
        Statistics s = getStatistics();
        return s.getCollectionFetchCount() > s.getCollectionLoadCount() * 2;
    }
}
```

---

## HikariCP Connection Pool Tuning

```yaml
spring:
  datasource:
    hikari:
      # Pool sizing
      maximum-pool-size: 20        # Max connections to DB
      minimum-idle: 5              # Always-ready connections

      # Timeouts
      connection-timeout: 30000    # 30s: how long to wait for pool to give connection
      idle-timeout: 600000         # 10 min: remove idle connections above minimum
      max-lifetime: 1800000        # 30 min: recycle connections (before DB server kills them)

      # Validation
      connection-test-query: SELECT 1
      validation-timeout: 5000

      # Monitoring
      leak-detection-threshold: 60000  # Warn if connection held >60s (dev only!)
      pool-name: OrderServiceHikariPool
      register-mbeans: true          # JMX monitoring

      # Performance (PostgreSQL specific)
      data-source-properties:
        cachePrepStmts: true
        prepStmtCacheSize: 250
        prepStmtCacheSqlLimit: 2048
```

**Sizing formula:**
```
connections = (core_count × 2) + effective_spindle_count

Web app, 4 cores: 4×2+1 = ~10 connections
Web app, 8 cores: 8×2+1 = ~17 connections

Start with 10-20, monitor, adjust based on wait times.
```

**Signs of pool misconfiguration:**
- Too small: `Connection timeout: Pool not available` errors
- Too large: CPU context-switching, memory waste
- Leaks: `HikariPool connection leak detected` (fix: find code holding connections too long)

---

## JDBC Batching Configuration

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 20           # Insert/update 20 rows per batch
          fetch_size: 50           # Fetch 50 rows per DB round trip
        order_inserts: true        # Group INSERT statements (required for batching)
        order_updates: true        # Group UPDATE statements
```

**Without batching:** 100 inserts = 100 individual round trips
**With batching:** 100 inserts = 5 round trips (batch_size=20)

---

## Performance Decision Matrix

| Use Case | Best Approach |
|---|---|
| Detail page (single entity) | JOIN FETCH |
| List page (summary only) | DTO Projection |
| List page (with children, no pagination) | JOIN FETCH with DISTINCT |
| Paginated list with children | Two-query (page IDs, then JOIN FETCH) |
| Multiple lazy collections needed | @BatchSize on each |
| Detecting issues | Hibernate Statistics + show-sql |
| Bulk operations | JDBC batching (batch_size + order_inserts) |

---

## Key Interview Q&A

**Q: What is the N+1 problem and how do you detect it?**
Loading N parent entities then issuing N additional queries for each entity's lazy relationship. 100 orders + items = 101 queries. Detection: `show-sql: true` to see repeated queries, or Hibernate statistics where `collectionFetchCount` is much higher than `collectionLoadCount`.

**Q: Four solutions to N+1?**
1. JOIN FETCH — single query with JOIN (best for detail views)
2. @BatchSize — batches lazy loads with IN clause (good for lists)
3. DTO Projection — don't load relationship at all (best for summaries)
4. @EntityGraph — declarative JOIN FETCH (reusable)

**Q: Why must you override @ManyToOne to LAZY?**
Default is EAGER. Every OrderItem load also loads its Order — N+1 in reverse direction. 100 items = 101 queries. Always override: `@ManyToOne(fetch = FetchType.LAZY)`.

**Q: Why can't you paginate with JOIN FETCH directly?**
JOIN FETCH = Cartesian product = rows multiplied. An Order with 3 items = 3 rows. `LIMIT 20` doesn't mean 20 orders — means 20 rows (~7 orders). Hibernate loads ALL data into memory then slices. Causes OutOfMemoryError with large datasets. Use two-query pattern.

**Q: What is open-in-view and why should it be false?**
Open Session In View keeps Hibernate session alive for entire HTTP request including view rendering. Causes: hidden N+1 during JSON serialization, holds DB connections longer (pool exhaustion under load), tight coupling between web and persistence. Always `spring.jpa.open-in-view: false`.

**Q: How do you size a connection pool?**
Formula: `(cores × 2) + spindle_count`. 8-core machine: ~20 connections. More is NOT better — context switching. Monitor: if threads waiting for connections → increase pool. If many idle connections → decrease. HikariCP `maximumPoolSize` = 20 is a good starting point.

**Q: What is @BatchSize and how does it work?**
Hibernate loads lazy collections in batches using SQL IN clause instead of N individual queries. `@BatchSize(size=10)` on collection → 100 orders = 10 queries (`WHERE order_id IN (1..10)`, `WHERE order_id IN (11..20)`, ...) instead of 100. Also configure globally with `default_batch_fetch_size`.
