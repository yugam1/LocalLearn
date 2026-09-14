# JPA Entities, Relationships & HikariCP
**✅ Completed · plan: Phase 1, Task 3** — Entity mapping, cascade/orphanRemoval, fetch types, pool config

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Hibernate is a **bookkeeper standing between your objects and
SQL**. Entities are its ledger entries; every annotation is an instruction about
*when* it may write SQL (cascade, fetch) and *what shape* (columns, keys). Most
production JPA pain is one of two mistakes: letting a default decide fetching, or
forgetting the bookkeeper only sees changes made through the entity graph.

```
Order (parent)  1 ──────── * OrderItem (child)
  @OneToMany(mappedBy="order",          @ManyToOne(fetch = LAZY)  ← FK lives HERE
             cascade = ALL,             @JoinColumn("order_id")
             orphanRemoval = true)          = the OWNING side
```

**Five rules you must never get wrong:**
1. **The `@ManyToOne` side owns the relationship** — it holds the FK column; `mappedBy` marks the mirror.
2. `@ManyToOne` and `@OneToOne` default to **EAGER — always override to LAZY**.
3. `cascade = REMOVE` fires when you delete the parent; `orphanRemoval = true` fires when you *remove a child from the collection*.
4. `open-in-view: false`, `ddl-auto: none` in prod (Flyway), `@Enumerated(EnumType.STRING)` always.
5. Pool sizing: `(cores × 2) + spindles` — **more connections is not faster**.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Bidirectional Order↔OrderItem. You do <code>order.getItems().add(item)</code> but never <code>item.setOrder(order)</code>, then save the order (cascade ALL). What lands in <code>order_items.order_id</code>?</summary>

**NULL** (or a constraint violation if the column is NOT NULL). The FK is written
from the **owning side** — the child's `order` field — and you never set it.
`mappedBy` is a mirror, not a source of truth. This is why `addItem()` helper
methods that set both sides exist.
</details>

<details>
<summary><b>P2.</b> Entity has an enum status with default <code>@Enumerated</code> (ORDINAL). Six months later someone inserts a new enum constant <em>in the middle</em> of the enum. What happens to existing rows?</summary>

**Silent data corruption.** ORDINAL stores the position (0,1,2…). Insert
`PROCESSING` between `PENDING`(0) and `CONFIRMED`(1) and every stored `1` now
deserializes as `PROCESSING`. No error, wrong meaning. `EnumType.STRING` costs a
few bytes and is immune.
</details>

<details>
<summary><b>P3.</b> Load an order, then <code>order.getItems().clear()</code> inside a transaction, with <code>orphanRemoval = true</code>. What SQL runs at commit? And with only <code>cascade = ALL</code> (no orphanRemoval)?</summary>

With orphanRemoval: **DELETE for every item** — removal from the collection means
"this child has no reason to exist." With only cascade ALL: the items would just
be disassociated (FK set to null, or an error if NOT NULL) — cascade REMOVE only
triggers when the *parent itself* is deleted. This exact `clear()` + re-add
pattern is how `updateOrder()` replaces items.
</details>

---

## 📖 THE STORY

### 1. The stack, so errors make sense

Your code → Spring Data JPA (repository interfaces) → JPA spec → **Hibernate**
(the actual ORM) → JDBC → PostgreSQL. When a stack trace says `Hibernate` or
`org.hibernate.dialect`, you're below the Spring abstraction — that's where
fetching, dirty checking, and SQL generation actually happen.

### 2. Identity — how rows get their ids

| Strategy | Behavior | Trade-off |
|---|---|---|
| `IDENTITY` | DB auto-increment | insert must hit the DB immediately → **kills JDBC insert batching** |
| `SEQUENCE` | DB sequence, pre-allocated blocks | Hibernate knows ids up front → batching works; PostgreSQL preferred |
| `UUID` | app-generated | distributed systems; index bloat caveat |
| `AUTO` / `TABLE` | provider guesses / id table | avoid — unpredictable / slow |

### 3. Relationships — who owns the FK

`@OneToMany(mappedBy = "order")` on `Order.items`; `@ManyToOne @JoinColumn(name
= "order_id")` on `OrderItem.order`. The child side is the **owning side**
because that's where the FK column physically is. Consequences:
- Hibernate persists the relationship from the owning side only (hence P1).
- Helper methods keep the in-memory graph consistent:

```java
public void addItem(OrderItem item)    { items.add(item);   item.setOrder(this); }
public void removeItem(OrderItem item) { items.remove(item); item.setOrder(null); }
```

- Lombok trap: `@Data` generates `toString`/`equals` including the back-reference
  → **StackOverflowError** on circular graphs. Always
  `@ToString(exclude = "items")` / `@EqualsAndHashCode(exclude = "items")` (and
  the mirror on the child).

### 4. Cascade & orphanRemoval — lifecycle coupling

Cascade = "when I do X to the parent, do X to the children": `PERSIST`, `MERGE`,
`REMOVE`, `REFRESH`, `DETACH`, or `ALL`. Use `ALL + orphanRemoval = true` for
**composition** — an OrderItem has no life outside its Order. Don't cascade to
entities with independent life (a Product referenced by items must survive an
order's deletion).

### 5. Fetch types — the defaults are a trap

| Relationship | Default | Do this |
|---|---|---|
| `@OneToMany`, `@ManyToMany` | LAZY | keep |
| `@ManyToOne`, `@OneToOne` | **EAGER** | **override to LAZY** |

EAGER `@ManyToOne` means loading 100 items also loads 100 parent orders — one
query each. LAZY everywhere + explicit `JOIN FETCH`/`@EntityGraph` where you
actually need the association ([task 6](06-n-plus-one-hikaricp-tuning.md) measures the damage).

### 6. Config that separates juniors from seniors

```yaml
spring:
  jpa:
    hibernate.ddl-auto: update       # dev ONLY — prod = none + Flyway
    open-in-view: false              # ALWAYS
    properties.hibernate:
      jdbc.batch_size: 20
      order_inserts: true
      order_updates: true
```

- `ddl-auto`: `create`/`create-drop` (tests), `update` (dev), `validate`
  (pre-prod), **`none` in prod** — schema changes are migrations, not side effects.
- **OSIV** keeps the Hibernate session (and often the connection) open through
  view rendering: hidden lazy loads in the serializer, connections held longer,
  pool exhaustion under load. `open-in-view: false` forces you to fetch
  consciously inside the service/transaction.
- `@CreationTimestamp` (+ `updatable = false`) / `@UpdateTimestamp` — auditing
  columns with zero code.

### 7. HikariCP — the pool is a shared bottleneck, size it small

```yaml
spring.datasource.hikari:
  maximum-pool-size: 20        # (cores × 2) + spindles — NOT "as many as possible"
  minimum-idle: 5
  connection-timeout: 30000    # wait for a connection before failing
  max-lifetime: 1800000        # recycle before DB/firewall kills it
  leak-detection-threshold: 60000   # dev: warn if a connection is held > 60s
```

Why small: the DB can only truly run ~cores queries at once; extra connections
just queue *inside* PostgreSQL with added context-switching. A 200-connection
pool is slower than a 20-connection pool. Every long transaction ([task 5](05-transactions-isolation-locking.md))
holds one of these — that's the link between "slow code" and "whole app down."

---

## 🛠 BUILD REFERENCE

<details>
<summary><b>Dependencies + Postgres via Docker</b></summary>

`spring-boot-starter-data-jpa`, `postgresql` (runtime), `h2` (test).

```bash
docker run --name postgres-order-service -e POSTGRES_USER=orderuser \
  -e POSTGRES_PASSWORD=orderpass -e POSTGRES_DB=orderdb -p 5432:5432 -d postgres:15
```

```yaml
spring.datasource:
  url: jdbc:postgresql://localhost:5432/orderdb
  username: orderuser
  password: orderpass
```
</details>

<details>
<summary><b>Order entity (full annotations)</b></summary>

```java
@Entity @Table(name = "orders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = "items") @EqualsAndHashCode(exclude = "items")
@NamedEntityGraph(name = "Order.withItems", attributeNodes = @NamedAttributeNode("items"))
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version private Long version;                    // optimistic locking (task 5)

    @Column(name = "order_number", nullable = false, unique = true, length = 50)
    private String orderNumber;
    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;
    @Column(name = "customer_email", nullable = false, length = 100)
    private String customerEmail;

    @Enumerated(EnumType.STRING)                      // never ORDINAL
    @Column(nullable = false, length = 20)
    private OrderStatus status;                       // PENDING..REFUNDED enum

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 10)                             // N+1 mitigation (task 6)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @CreationTimestamp @Column(name = "order_date", nullable = false, updatable = false)
    private LocalDateTime orderDate;
    @UpdateTimestamp @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    public void addItem(OrderItem i)    { items.add(i); i.setOrder(this); }
    public void removeItem(OrderItem i) { items.remove(i); i.setOrder(null); }
    public void calculateTotal() {
        totalAmount = items.stream().map(OrderItem::getLineTotal)
                           .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```
</details>

<details>
<summary><b>OrderItem entity (owning side)</b></summary>

```java
@Entity @Table(name = "order_items")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = "order") @EqualsAndHashCode(exclude = "order")
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)   // override EAGER default!
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;
    @Column(nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Transient   // computed, not a column
    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
```
</details>

<details>
<summary><b>Repository + service pattern</b></summary>

```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByOrderNumber(String orderNumber);   // derived queries
    List<Order> findByStatus(OrderStatus status);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id); // one SQL, no N+1
}
```

Service: class-level `@Transactional(readOnly = true)`, method-level
`@Transactional` on writes. `createOrder` builds parent, `addItem()`s children,
`calculateTotal()`, one `save()` — cascade persists items.
`updateOrder`: load with items → `items.clear()` (orphanRemoval deletes) → re-add.
</details>

<details>
<summary><b>Smoke test</b></summary>

```bash
curl -X POST localhost:8080/api/v1/orders -H "Content-Type: application/json" -d '{
  "customerName":"John Doe","customerEmail":"john@example.com",
  "items":[{"productName":"MacBook Pro","quantity":1,"unitPrice":2999.00},
           {"productName":"Magic Mouse","quantity":2,"unitPrice":79.99}]}'
# 201, total 3158.98; console shows ONE insert-order + batched item inserts
docker exec -it postgres-order-service psql -U orderuser -d orderdb -c "SELECT * FROM order_items;"
```
</details>

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> In a bidirectional @OneToMany/@ManyToOne, which side owns the relationship and what does that mean in practice?</summary>

The @ManyToOne (child) side — it holds the FK column via @JoinColumn. Hibernate
writes the FK only from that side; `mappedBy` marks the inverse. Practical rule:
always set both sides via helper methods, or the FK ends up null.
</details>

<details><summary><b>Q2.</b> cascade = REMOVE vs orphanRemoval = true — trigger for each?</summary>

REMOVE: children deleted when the **parent entity is deleted**. orphanRemoval:
child deleted when it is **removed from the parent's collection** (parent lives
on). Composition (Order→Items) wants both: `cascade = ALL, orphanRemoval = true`.
</details>

<details><summary><b>Q3.</b> Which associations default to EAGER, and why is that dangerous?</summary>

@ManyToOne and @OneToOne. EAGER means every child fetch drags its parent —
loading N items issues up to N extra order queries (reverse N+1), and you can't
turn EAGER off per query, only per mapping. Override both to LAZY at declaration.
</details>

<details><summary><b>Q4.</b> Why open-in-view: false?</summary>

OSIV holds the Hibernate session across view rendering/serialization: DB
connections held for the whole HTTP request (pool exhaustion), lazy loads firing
from the JSON serializer (invisible N+1), persistence leaking into the web layer.
Disabling forces deliberate fetching inside transactions.
</details>

<details><summary><b>Q5.</b> ddl-auto in prod, and the pool sizing formula?</summary>

`none` — schema changes go through Flyway migrations (versioned, reviewed,
reversible). Pool: `(cores × 2) + effective_spindles` ≈ 10–20; bigger pools add
context switching and queueing inside the DB, not throughput.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why does IDENTITY generation disable insert batching, and what's the fix?</summary>

With IDENTITY the id is only known after the row is inserted, so Hibernate must
flush each INSERT individually to get the key back. SEQUENCE pre-allocates ids,
letting Hibernate queue INSERTs and send them as one JDBC batch
(`jdbc.batch_size` + `order_inserts`).
</details>

<details><summary><b>Q7.</b> Why is @Enumerated(EnumType.ORDINAL) a time bomb?</summary>

It stores the constant's position. Any reordering or mid-enum insertion
reinterprets existing rows as different constants — silent corruption, no
exception. STRING is self-describing and refactor-safe (rename needs a data
migration, but that's visible).
</details>

<details><summary><b>Q8.</b> What breaks if you use Lombok @Data unmodified on bidirectional entities?</summary>

Generated toString/equals/hashCode traverse the cycle Order→items→order→… →
StackOverflowError; also equals/hashCode over mutable fields breaks collection
membership after persist (id changes). Exclude back-references from both, or
hand-write id-based equals.
</details>

<details><summary><b>Q9.</b> What is max-lifetime protecting you from?</summary>

Infrastructure (DB, firewall, LB) silently killing idle TCP connections. Hikari
retires connections before that age, so the app never borrows a dead connection.
Set it below the infra timeout (default 30 min).
</details>

---

## 🃏 FLASHCARDS

```
Owning side of @OneToMany/@ManyToOne	The @ManyToOne child — it has the FK column; mappedBy = mirror
EAGER-by-default associations	@ManyToOne and @OneToOne — always override to LAZY
orphanRemoval trigger	Child removed from parent's collection (parent still alive)
Enum mapping rule	@Enumerated(EnumType.STRING) — ORDINAL corrupts on reorder
IDENTITY vs SEQUENCE	IDENTITY blocks insert batching (id only after insert); SEQUENCE pre-allocates
ddl-auto prod value	none — Flyway owns the schema
OSIV setting	open-in-view: false, always
HikariCP sizing formula	(cores × 2) + spindles; small pools beat big pools
@CreationTimestamp pairing	updatable = false so updates can't touch it
Bidirectional consistency	addItem/removeItem helpers set BOTH sides
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| @Version column | CAS optimistic concurrency | `01-foundations/05`, `02-concurrency/03` |
| LAZY + explicit fetch | N+1 measurement & fixes | `01-foundations/06` |
| pool exhaustion via held connections | short transactions rule | `01-foundations/05` |
| ddl-auto none + migrations | Flyway | `13-database-advanced` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
