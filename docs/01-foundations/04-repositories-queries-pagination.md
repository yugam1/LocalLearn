# Specifications, Projections & Pagination
**✅ Completed · plan: Phase 1, Task 4** — Dynamic queries, DTO projections, Page vs Slice, paginated JOIN FETCH

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** The repository layer has two axes: **which rows** (derived
queries → @Query → Specifications, in rising flexibility) and **which columns**
(whole entity → projection). Senior-level JPA = never fetching more rows *or*
columns than the use case needs — and knowing that **pagination and JOIN FETCH
fight each other**, resolved by the two-query pattern.

```
which ROWS?   findByStatus()  →  @Query JPQL  →  Specification (composable filters)
which COLS?   full entity     →  interface/class projection (SELECT only what's needed)
how MANY?     List → Slice (has next?) → Page (+ COUNT query)
```

**Five rules you must never get wrong:**
1. A `Specification` is a **null-safe, composable predicate**: `where(a).and(b).and(c)` — null filter → `cb.conjunction()` (no-op).
2. **Page runs a COUNT query; Slice doesn't.** Infinite scroll → Slice; page numbers → Page.
3. **Never `JOIN FETCH` a collection + `Pageable` directly** — Hibernate paginates *in memory* (HHH90003004). Use two queries: page the IDs, then fetch by IDs.
4. Projections make Hibernate `SELECT` only those columns — a read-only list view should never load full entities.
5. `@EntityGraph` and `JOIN FETCH` produce the same SQL — EntityGraph is declarative/reusable, JOIN FETCH lives in the query string.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> <code>@Query("SELECT o FROM Order o LEFT JOIN FETCH o.items")</code> with a <code>Pageable</code> parameter, 1M orders in the table. The query works. What does the log warn, and what actually happened in memory?</summary>

`HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory!`
The join multiplies rows (one per item), so SQL LIMIT would cut orders mid-way —
Hibernate therefore fetched **all 1M orders + items into the JVM** and paginated
the list in memory. Works in dev, OOMs in prod. Fix: two-query pattern.
</details>

<details>
<summary><b>P2.</b> A dashboard needs id + orderNumber + totalAmount for 10,000 orders. Option A: <code>findAll()</code> then map to DTO. Option B: interface projection. Same SQL?</summary>

No. A does `SELECT *` on every column of every order (plus entity overhead:
dirty-check snapshots, persistence context bookkeeping). B generates
`SELECT o.id, o.order_number, o.total_amount` — a fraction of the I/O and no
managed entities at all. Mapping "later in Java" doesn't un-fetch the columns.
</details>

<details>
<summary><b>P3.</b> Search endpoint with optional filters email/status/minTotal. User passes only status. What must <code>hasCustomerEmail(null)</code> return for the chain <code>where(email).and(status).and(total)</code> to still work?</summary>

A specification that returns `cb.conjunction()` — the always-true predicate, i.e.
"no filter." That's the null-safety convention that makes specs composable: each
spec ignores itself when its parameter is null, so one chain serves every filter
combination without if-else query building.
</details>

---

## 📖 THE STORY

### 1. Three tiers of "which rows"

- **Derived queries** (`findByStatusAndCustomerEmail(...)`) — free, but explode
  combinatorially with optional filters.
- **@Query JPQL** — full control, fixed shape.
- **Specifications** — when filters are *optional and combine*. Enable with
  `JpaSpecificationExecutor<Order>` on the repository, then:

```java
public class OrderSpecification {
    public static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, cb) -> status == null
            ? cb.conjunction()                       // no-op when absent
            : cb.equal(root.get("status"), status);
    }
    public static Specification<Order> totalBetween(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return cb.conjunction();
            if (min == null) return cb.lessThanOrEqualTo(root.get("totalAmount"), max);
            if (max == null) return cb.greaterThanOrEqualTo(root.get("totalAmount"), min);
            return cb.between(root.get("totalAmount"), min, max);
        };
    }
}

// service — ONE line handles 2^n filter combinations
Specification<Order> spec = Specification
    .where(hasCustomerEmail(email)).and(hasStatus(status)).and(totalBetween(min, max));
Page<Order> page = orderRepository.findAll(spec, pageable);
```

Under the hood it's the JPA Criteria API: `root` = FROM, `cb` = predicate
factory, type-safe-ish (field names still strings — typos fail at runtime).

### 2. Page vs Slice — you're paying for a COUNT

`Page<T>` = content + `totalElements`/`totalPages` → **runs a second COUNT
query** every call. `Slice<T>` = content + `hasNext()` (fetches size+1 rows) —
no COUNT. On big/filtered tables COUNT can cost more than the page itself.
Numbered pagination UI → Page; infinite scroll/"load more" → Slice.

`Pageable` also carries sorting: `PageRequest.of(0, 20, Sort.by("orderDate").descending())`.

### 3. The pagination × JOIN FETCH conflict

SQL `LIMIT 20` limits *rows*, but a fetch-join makes one order occupy N rows.
Hibernate refuses to return half an order, so it silently drops LIMIT and
paginates in the JVM. The professional fix — **two queries**:

```java
@Query("SELECT o.id FROM Order o")                       // 1) page ids in the DB
Page<Long> findAllOrderIds(Pageable pageable);

@Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids")
List<Order> findByIdInWithItems(@Param("ids") List<Long> ids);   // 2) fetch graph
```

Two round trips, both index-friendly, memory bounded. (Alternative for mild
cases: no fetch join + `@BatchSize` — [task 6](06-n-plus-one-hikaricp-tuning.md).)

### 4. Projections — which columns

```java
// Interface projection: Spring generates the impl, Hibernate SELECTs only these
public interface OrderSummary {
    Long getId(); String getOrderNumber(); BigDecimal getTotalAmount();
}
List<OrderSummary> findAllProjectedBy();

// Class projection: JPQL constructor expression — explicit, works with joins/aggregates
@Query("SELECT new com.ecommerce.dto.OrderSummaryDTO(o.id, o.orderNumber, COUNT(i)) " +
       "FROM Order o LEFT JOIN o.items i GROUP BY o.id, o.orderNumber")
List<OrderSummaryDTO> findSummariesWithItemCount();
```

Interface projections: least code, supports nested/`@Value` SpEL (open
projections — but those fetch the full entity, losing the benefit). Class
projections: compile-checked constructor, immutable. Either way: no persistence
context, no dirty checking — ideal for read paths.

### 5. @EntityGraph — declarative fetch plans

```java
@EntityGraph(attributePaths = "items")     // or the @NamedEntityGraph("Order.withItems")
List<Order> findByStatus(OrderStatus status);
```

Same LEFT JOIN SQL as JOIN FETCH, but attached to the *method*, not embedded in
a query string — so one derived query can exist in with-items and without-items
variants. Same pagination caveat applies (it's still a collection fetch).

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> What problem do Specifications solve that derived queries can't, and how do you enable them?</summary>

Dynamic *optional* filter combinations — 5 optional filters would need 2⁵ derived
methods or string-built JPQL. Specs are composable predicates chained with
where/and/or; null parameter → `cb.conjunction()` no-op. Enable: repository
extends `JpaSpecificationExecutor<T>`; query with `findAll(spec, pageable)`.
</details>

<details><summary><b>Q2.</b> Page vs Slice — mechanism and when each?</summary>

Page runs an extra COUNT query to know totalElements/totalPages — needed for
numbered pagination. Slice fetches size+1 rows to answer only hasNext() — no
COUNT, cheaper on large tables; right for infinite scroll.
</details>

<details><summary><b>Q3.</b> Why can't you paginate a JOIN FETCH directly, and what's the fix?</summary>

The fetch join multiplies rows per parent, so SQL LIMIT would truncate a parent's
collection; Hibernate instead loads everything and paginates in memory
(HHH90003004) — OOM risk. Fix: two-query pattern — page the IDs (DB-level
LIMIT), then JOIN FETCH WHERE id IN (:ids).
</details>

<details><summary><b>Q4.</b> Interface vs class projection?</summary>

Interface: getters only, Spring proxies it, Hibernate selects just those columns;
supports nested projections. Class: JPQL `new ...DTO(...)` constructor expression;
compile-time checked, immutable, works naturally with aggregates. Both bypass the
persistence context (no dirty checking).
</details>

<details><summary><b>Q5.</b> @EntityGraph vs JOIN FETCH?</summary>

Same generated SQL (LEFT JOIN, association initialized). EntityGraph is
declarative metadata on the repository method — reusable across derived queries;
JOIN FETCH is written inside a @Query string. Both hit the in-memory-pagination
trap with collections + Pageable.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why does the two-query pattern need DISTINCT (or a Set) on the second query?</summary>

The LEFT JOIN FETCH returns one row per (order, item) pair, so an order with 3
items appears 3 times in the JDBC result. DISTINCT (in JPQL — Hibernate 6
deduplicates entities without passing it to SQL) collapses duplicates in the
entity list.
</details>

<details><summary><b>Q7.</b> When does an interface projection silently lose its performance benefit?</summary>

Open projections — a `@Value("#{target.customerName + ...}")` SpEL getter forces
Spring to load the **full entity** as `target`, then compute on top. Closed
projections (plain getters matching properties) keep the narrow SELECT.
</details>

<details><summary><b>Q8.</b> Sorting by a field the client supplies — what's the injection story with Pageable vs native queries?</summary>

`Sort` properties go through the JPA metamodel — invalid property names throw
`PropertyReferenceException`, not SQL injection. In *native* queries, sort/column
names concatenated into SQL ARE injectable — whitelist them explicitly.
</details>

---

## 🃏 FLASHCARDS

```
Enable Specifications	Repository extends JpaSpecificationExecutor<T>
Null filter inside a Specification	Return cb.conjunction() — always-true no-op
Page's hidden cost	A second COUNT query per call
Slice mechanism	Fetch pageSize+1 rows → hasNext(), no COUNT
HHH90003004 means	Collection fetch + pagination → Hibernate paginated IN MEMORY
Paginated JOIN FETCH pattern	Query 1: page IDs; Query 2: JOIN FETCH WHERE id IN :ids
Interface projection SQL	SELECT only the getter-matched columns
Class projection syntax	SELECT new com.x.Dto(o.a, o.b) FROM ...
@EntityGraph vs JOIN FETCH	Same SQL; graph = declarative per-method, fetch = in query string
Projections and dirty checking	None — no managed entities on read paths
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| projection = fetch only the contract | DTO at the API boundary | `01-foundations/01` |
| two-query pattern | @BatchSize batched IN loads | `01-foundations/06` |
| COUNT query cost | Hibernate statistics / query counting | `01-foundations/06` |
| pagination standards for APIs | SpringDoc / API design | `14-api-documentation` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
