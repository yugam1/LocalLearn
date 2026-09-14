# JPA Performance — N+1, Batch Fetching, Pool Tuning
**✅ Completed · plan: Phase 1, Task 6** — Detecting and killing N+1, @BatchSize, Hibernate statistics, HikariCP + JDBC batching

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Every lazy association is a **loaded spring** — touch it outside
a fetch plan and it fires one more SQL query. N+1 is never one bug; it's a
*default* (LAZY firing per-row, or worse, EAGER firing everywhere). The cure is
always the same question: **for this use case, what do I actually need — and can
I fetch it in one plan?**

```
findAll()          → SELECT * FROM orders                 (1 query)
loop items.size()  → SELECT * FROM order_items WHERE order_id = ?   × N
                     100 orders = 101 queries, 10×+ slower
```

**Five rules you must never get wrong:**
1. **Fix ladder:** JOIN FETCH (detail view) → `@BatchSize` (lists) → DTO projection (summaries — fetch *nothing*) → `@EntityGraph` (declarative).
2. `@ManyToOne`/`@OneToOne` default EAGER = **N+1 in reverse** — override to LAZY at the mapping.
3. Detection = evidence, not vibes: `generate_statistics: true`, N+1 smell = `collectionFetchCount >> collectionLoadCount`.
4. Two `List` fetch-joins → **MultipleBagFetchException**; pagination + fetch-join → in-memory pagination. Both have standard escapes.
5. Batching: `jdbc.batch_size: 20` + `order_inserts: true` turns 100 round trips into 5 — but **not with IDENTITY ids**.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> You add <code>@BatchSize(size = 10)</code> to <code>Order.items</code> and load 100 orders, then touch every collection. How many item queries now, and what do they look like?</summary>

**10 queries**, each `SELECT * FROM order_items WHERE order_id IN (?,?,...,?)`
with 10 ids. @BatchSize doesn't stop lazy loading — it makes the *next* lazy hit
piggyback its neighbors: "while I'm going to the DB for order 1's items, grab
items for the 9 other uninitialized orders in the persistence context too."
101 → 11 queries with one annotation.
</details>

<details>
<summary><b>P2.</b> <code>SELECT o FROM Order o LEFT JOIN FETCH o.items LEFT JOIN FETCH o.payments</code> — both are <code>List</code>. What happens at bootstrap/runtime?</summary>

**MultipleBagFetchException.** Two "bags" (unordered Lists) fetch-joined at once
create a Cartesian product Hibernate can't disambiguate (items × payments rows).
Escapes: make one/both a `Set`, split into two queries
(`Hibernate.initialize(...)`), or fetch-join one and `@BatchSize` the other.
</details>

<details>
<summary><b>P3.</b> Entities use <code>GenerationType.IDENTITY</code>. You configure <code>jdbc.batch_size: 20</code> and insert 100 items. How many round trips?</summary>

**Still 100.** IDENTITY means the id is only known after each INSERT executes, so
Hibernate must flush row-by-row — batch_size is silently ignored for inserts.
Batching needs SEQUENCE (ids pre-allocated). This is the hidden coupling between
id strategy ([task 3](03-jpa-entities-relationships.md)) and write throughput.
</details>

---

## 📖 THE STORY

### 1. Know your enemy: both directions of N+1

**Forward** (parent → lazy children): load N orders, touch each `items` → N extra
queries. **Reverse** (child → EAGER parent): load N items, each drags its Order →
N extra queries, *and you can't turn EAGER off per-query* — which is why the fix
lives at the mapping: `@ManyToOne(fetch = FetchType.LAZY)`.

### 2. Detection — prove it with numbers

```yaml
spring.jpa:
  show-sql: true
  properties.hibernate.generate_statistics: true
```

```java
Statistics s = emf.unwrap(SessionFactory.class).getStatistics();
// The tell: collectionFetchCount >> collectionLoadCount  →  N+1
// Also watch: queryExecutionCount, prepareStatementCount, connectCount
```

`collectionLoadCount` = how many collections were initialized;
`collectionFetchCount` = how many *separate SQL fetches* it took. Healthy code
fetches many collections per query; N+1 code fetches one per query. Wire this
into an admin endpoint and you can diagnose prod without a profiler.

### 3. The fix ladder — match tool to view

| Use case | Fix | Why |
|---|---|---|
| Detail page (one entity + children) | **JOIN FETCH** | one SQL, exactly what you need |
| List with children, no pagination | JOIN FETCH + DISTINCT | one SQL; Hibernate 6 dedups entities |
| List with children, **paginated** | **two-query pattern** (page ids → fetch by ids) | avoids in-memory pagination |
| Summary/list view (no children needed) | **DTO projection** | fetches *nothing* extra — fastest is the query you don't run |
| Many lazy collections / can't touch queries | **@BatchSize** | N queries → N/size IN-queries, zero query rewrites |
| Reusable fetch plan on derived queries | **@EntityGraph** | same SQL as JOIN FETCH, declared per method |

```java
@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(@Param("id") Long id);          // detail

@Query("SELECT new com.ecommerce.dto.OrderSummaryDTO(o.id, o.orderNumber, o.totalAmount) FROM Order o")
List<OrderSummaryDTO> findAllSummaries();                          // summary — no items at all

@EntityGraph(attributePaths = {"items"})
List<Order> findByStatus(OrderStatus status);                      // declarative
```

Global batch default: `hibernate.default_batch_fetch_size: 10` (then per-mapping
`@BatchSize` only where you want a different size).

### 4. The two collisions everyone hits

**Fetch-join × pagination** → HHH90003004, all rows into memory (full mechanics
and the two-query pattern: [task 4](04-repositories-queries-pagination.md)). Keep `PageImpl(orders,
pageable, idPage.getTotalElements())` to preserve page metadata.

**Fetch-join × two List collections** → MultipleBagFetchException (see P2).
Rule of thumb: fetch-join **at most one collection** per query; batch the rest.

### 5. Write-side performance — JDBC batching

```yaml
spring.jpa.properties.hibernate:
  jdbc: { batch_size: 20, fetch_size: 50 }
  order_inserts: true      # group same-table INSERTs so they can batch
  order_updates: true
```

100 inserts: unbatched = 100 round trips; batched = 5. Requires SEQUENCE ids
(P3) and `order_inserts` when interleaving parent/child persists.

### 6. HikariCP tuning — the checklist

```yaml
spring.datasource.hikari:
  maximum-pool-size: 20            # (cores × 2) + spindles; start 10–20
  minimum-idle: 5
  connection-timeout: 30000        # fail fast-ish instead of hanging forever
  idle-timeout: 600000
  max-lifetime: 1800000            # recycle before infra kills the socket
  leak-detection-threshold: 60000  # DEV: logs stack of code holding conn >60s
  register-mbeans: true            # JMX: active/idle/waiting metrics
```

Symptom table: *"Connection is not available, request timed out"* → pool too
small **or** transactions too long (check leak detection first — it names the
culprit code). Many idle connections → shrink. High CPU + big pool → shrink.
The pool is where every earlier sin (long tx, OSIV, N+1 latency) finally shows
up as an outage.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Define N+1, give the query count for 100 orders, and name the detection signal in Hibernate statistics.</summary>

1 query loads N parents, then N more load each parent's lazy association —
100 orders = 101 queries. Detection: `generate_statistics: true`;
`collectionFetchCount` far above `collectionLoadCount` (each collection needed
its own SQL fetch).
</details>

<details><summary><b>Q2.</b> The four fixes for N+1 and the use case each wins.</summary>

JOIN FETCH — detail views, one SQL. @BatchSize — list views/legacy code, turns N
loads into N/size IN-queries. DTO projection — summary views, loads no
association at all (fastest). @EntityGraph — same SQL as JOIN FETCH but
declarative and reusable on derived queries.
</details>

<details><summary><b>Q3.</b> How does @BatchSize actually work under the hood?</summary>

When one lazy collection initializes, Hibernate looks in the persistence context
for up to (size−1) other uninitialized collections of the same role and loads
them together with `WHERE order_id IN (...)`. It's lazy loading with carpooling —
no query changes needed.
</details>

<details><summary><b>Q4.</b> Why is EAGER @ManyToOne worse than a forward N+1?</summary>

Forward N+1 you can fix per-query (fetch when needed). EAGER is baked into the
mapping — *every* load of the child everywhere drags the parent, and no query
can opt out. That's why the rule is "override to LAZY at declaration, opt into
fetching per use case."
</details>

<details><summary><b>Q5.</b> Signs your Hikari pool is too small vs too large, and the formula.</summary>

Too small: threads queue on getConnection → "Connection is not available" timeouts.
Too large: DB-side context switching, memory, no extra throughput. Formula:
`(cores × 2) + effective_spindles` ≈ 10–20 for a typical service; tune on
wait-time metrics, not guesses.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> MultipleBagFetchException — cause and three escapes.</summary>

Fetch-joining two unordered `List` ("bag") collections in one query — the
Cartesian rows can't be attributed unambiguously. Escapes: (1) change to `Set`,
(2) split into two queries / `Hibernate.initialize`, (3) fetch-join one +
@BatchSize on the other. Guideline: max one collection fetch-join per query.
</details>

<details><summary><b>Q7.</b> What does leak-detection-threshold actually log, and why is it a dev/staging tool?</summary>

If a connection is borrowed longer than the threshold, Hikari logs the stack
trace of the borrowing code — pointing directly at the long transaction or
missed close. It costs overhead and produces false positives on legitimately
long jobs, so it's for diagnosis, not steady-state prod.
</details>

<details><summary><b>Q8.</b> Why does DTO projection beat even the perfect JOIN FETCH for a summary list?</summary>

JOIN FETCH still hydrates full entities into the persistence context (all
columns, dirty-check snapshots, memory). Projection selects three columns into
plain objects — less I/O, less GC, no session bookkeeping. The fastest
association fetch is the one you don't do.
</details>

<details><summary><b>Q9.</b> Trace the causal chain: N+1 in a hot endpoint → full outage.</summary>

N+1 inflates request latency → each request holds its pool connection longer →
pool utilization climbs → other requests queue at getConnection →
connection-timeout errors cascade app-wide (even endpoints without the bug) →
outage. The pool converts one slow query pattern into a system-wide failure.
</details>

---

## 🃏 FLASHCARDS

```
N+1 definition	1 parent query + N lazy-association queries (100 orders = 101)
Statistics smell for N+1	collectionFetchCount >> collectionLoadCount
Fix for detail view / summary view	JOIN FETCH / DTO projection (fetch nothing)
@BatchSize(10) on 100 collections	10 IN-queries instead of 100 (lazy carpooling)
Global batch setting	hibernate.default_batch_fetch_size: 10
Two List fetch-joins	MultipleBagFetchException — Set, split queries, or batch one
Fetch-join + Pageable	HHH90003004 in-memory pagination → two-query pattern
JDBC batching needs	jdbc.batch_size + order_inserts + SEQUENCE ids (not IDENTITY)
Pool too small symptom	getConnection timeouts: "Connection is not available"
leak-detection-threshold	Logs stack of code holding a connection too long (dev tool)
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| IDENTITY blocks insert batching | id strategy choice | `01-foundations/03` |
| two-query pagination | Specifications/Page mechanics | `01-foundations/04` |
| pool exhaustion chain | short transactions rule | `01-foundations/05` |
| statistics before optimizing | metrics-driven tuning (Micrometer) | `07-observability` |
| @BatchSize carpooling | Kafka batch consumer amortization | `04-kafka/02` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
