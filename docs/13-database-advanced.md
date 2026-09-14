# DB Advanced — Flyway, Multi-Tenancy, Indexing, Replicas
**⬜ Not Started · plan: Phase 11, Tasks 48–51** — Versioned migrations, tenant isolation, EXPLAIN-driven indexing, read routing

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** These four topics are the database growing up: **schema as
code** (Flyway — migrations are commits, the DB has a git history), **many
customers, one platform** (multi-tenancy — isolation level = business
decision), **queries earn their speed** (indexes are sorted copies you pay for
on every write; EXPLAIN is the receipts), and **reads scale out, writes
don't** (replicas + routing).

```
Flyway:   V1__create_orders.sql → V2__... applied in order, recorded in flyway_schema_history
Indexing: EXPLAIN ANALYZE → Seq Scan (bad) / Index Scan (good) / Index Only Scan (best)
Replicas: @Transactional(readOnly=true) ──▶ replica    @Transactional ──▶ primary
```

**Five rules you must never get wrong:**
1. Flyway owns the schema; `ddl-auto: none`. Applied migrations are **immutable** — never edit V3 after it ran (checksum mismatch); fix forward with V4.
2. Compound index **left-prefix rule**: `(email, status)` serves `email=?` and `email=? AND status=?` — never `status=?` alone.
3. An index is a write tax and a storage cost — create from **measured** EXPLAIN evidence, not vibes; check `pg_stat_user_indexes` for dead ones.
4. Multi-tenancy isolation ladder: separate DB > separate schema > shared table + discriminator — cost falls with isolation; schema-per-tenant (`search_path`) is the usual balance.
5. Replica routing rides `readOnly = true` — and replicas **lag**: read-your-own-writes flows must hit the primary.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> A teammate notices a typo in <code>V3__add_indexes.sql</code>, which already ran in staging and prod, edits the file in place, and redeploys. What happens at startup?</summary>

**Boot fails**: `validate-on-migrate` finds V3's checksum differs from the one
recorded in flyway_schema_history → "Migration checksum mismatch." Rightly so —
the file no longer describes what was actually applied. Recovery: revert the
edit and ship the fix as V4 (fix-forward), or, if the edit is truly cosmetic,
`flyway repair` to re-align checksums — knowingly rewriting history. Applied
migrations are immutable; that's the whole audit-trail promise.
</details>

<details>
<summary><b>P2.</b> Index: <code>(customer_email, status)</code>. Three queries: A) <code>WHERE customer_email=?</code> B) <code>WHERE customer_email=? AND status=?</code> C) <code>WHERE status='PENDING'</code>. Which use the index — and what are C's two proper fixes?</summary>

A and B (left-prefix satisfied). C can't — the index is sorted by email first;
statuses are scattered through it. Fixes: an index leading with status —
ideally **partial** (`ON orders(order_date) WHERE status='PENDING'`, tiny if
pending is 5% of rows) — or reorder to (status, email) IF email-alone queries
don't need serving. Column order in compound indexes is a design decision
driven by the query mix.
</details>

<details>
<summary><b>P3.</b> Read-replica routing on readOnly. A user updates their order; the confirmation page (readOnly service method) fetches it — and shows the OLD data. Nothing is "broken." What happened, and name two fixes.</summary>

Replication lag: the write hit primary; the read routed to a replica that
hadn't replayed it yet — stale read-your-own-write. Fixes: route
read-after-write flows to primary (drop readOnly on that path, or a "recent
writer" flag/sticky routing for N seconds); or return the updated entity from
the write call itself instead of re-querying. Replica lag is a feature of the
architecture — flows must be designed around it.
</details>

---

## 📖 THE STORY

### 1. Flyway — the schema's git

Naming contract: `V{n}__{description}.sql` (double underscore), applied in
version order, each recorded with checksum in `flyway_schema_history`;
`R__views.sql` repeatables re-run when their checksum changes. Config that
matters: `validate-on-migrate: true` (catch history tampering),
`out-of-order: false` (no V3-before-V2), `baseline-on-migrate` (adopting an
existing DB), and `ddl-auto: none` — one owner of the schema.

This project's migration set doubles as a Phase-1-to-2 recap: orders (+version
column for `@Version`), order_items (FK, ON DELETE CASCADE), the performance
indexes, processed_events (**UNIQUE event_id** — the idempotency ledger from
[task 11](04-kafka/02-reliability-dlt-idempotency.md) becomes DDL), products with stock/reserved.

Ops verbs: `flyway:info` (status), `migrate`, `validate`, `repair` (fix a
failed migration record), `clean` (drop everything — **wire it disabled in
prod**).

### 2. Multi-tenancy — whose data is this?

| Model | Isolation | Cost | Failure mode |
|---|---|---|---|
| DB per tenant | strongest | $$$, ops × N | connection-pool multiplication |
| **Schema per tenant** | strong | one cluster | migration × N schemas |
| Shared table + tenant_id | weakest | cheapest | one missing WHERE = data breach |

Schema-based implementation = three cooperating pieces:
1. **TenantContext** — a ThreadLocal set by a filter from `X-Tenant-ID`/JWT
   claim, cleared in finally (the MDC discipline again — and same async caveat:
   ThreadLocal doesn't cross into @Async without a decorator).
2. **CurrentTenantIdentifierResolver** — hands Hibernate the current tenant.
3. **MultiTenantConnectionProvider** — decorates each borrowed connection with
   `SET search_path TO <tenant>` and resets it on release (pooled connections
   are shared — a stale search_path = cross-tenant leak).

Wire via `hibernate.multiTenancy: SCHEMA` + the two class properties. Flyway
consequence: migrations must run per schema (iterate tenants).

### 3. Indexing — pay where the reads are

The menu beyond default B-Tree:
- **Compound** — left-prefix rule (P2); order columns by the query mix.
- **Partial** — `WHERE status = 'PENDING'`: index only the hot 5%, tiny and fast.
- **Covering** — `INCLUDE (order_number, total_amount)`: the query answers from
  the index alone → *Index Only Scan*, no heap visit.
- **GIN** — JSONB, arrays, full-text (`to_tsvector`).
- **BRIN** — huge append-only tables with natural ordering (order_date):
  kilobytes instead of gigabytes.

**EXPLAIN ANALYZE** is the arbiter (it *runs* the query — plain EXPLAIN
estimates): Seq Scan on a big filtered table = missing/unusable index;
`actual rows` wildly off `estimated rows` = stale stats → `ANALYZE`. Audit
usage via `pg_stat_user_indexes.idx_scan` (drop the zeros — they only tax
writes) and hunt via `pg_stat_statements` ordered by `mean_exec_time`.

Query patterns worth reflexes:
- Function on an indexed column disables it — `LOWER(email) = ?` needs an
  expression index `ON orders(LOWER(customer_email))`.
- `EXISTS (SELECT 1 ...)` over `IN (subquery)` — stops at first match.
- Deep pagination: OFFSET 200000 scans 200k rows; **keyset**
  (`WHERE id > :lastSeen ORDER BY id LIMIT 20`) is O(page) forever — trade:
  no jumping to page N. (Extends [task 4](01-foundations/04-repositories-queries-pagination.md)'s Page/Slice story.)

### 4. Read replicas — scaling the read side

`AbstractRoutingDataSource` with two targets; the lookup key comes from
`TransactionSynchronizationManager.isCurrentTransactionReadOnly()` — so the
class-level `@Transactional(readOnly = true)` pattern from
[task 5](01-foundations/05-transactions-isolation-locking.md) now has a second payoff: those methods transparently
hit the replica; writes hit primary. The catch is P3 — asynchronous
replication lags, so consistency-critical reads must opt back into primary.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Why Flyway over ddl-auto, in production terms?</summary>

Migrations are version-controlled, reviewed SQL applied in order and recorded
with checksums (audit trail, drift detection); ddl-auto diffs entities at
startup — unreviewed, unordered across team members, no history, can drop/alter
destructively. Prod: ddl-auto none, always.
</details>

<details><summary><b>Q2.</b> State the left-prefix rule and its design consequence.</summary>

A compound index (a, b, c) serves predicates on a / a,b / a,b,c — any prefix —
but not b or c alone (the index is sorted by a first). Consequence: column
order = your query mix ranked; the most universally-filtered column goes
first.
</details>

<details><summary><b>Q3.</b> Partial vs covering index — what does each optimize?</summary>

Partial (WHERE clause on the index): indexes only qualifying rows — small,
cheap to maintain, perfect for hot subsets (PENDING orders). Covering
(INCLUDE columns): the query's SELECT list lives in the index → Index Only
Scan, zero heap lookups — optimizes read latency at storage cost.
</details>

<details><summary><b>Q4.</b> The three multi-tenancy models with their trade and the schema-based mechanism in one line.</summary>

DB-per-tenant (max isolation, max cost), schema-per-tenant (strong isolation,
one cluster), shared-table+tenant_id (cheapest, one missing WHERE from a
breach). Schema-based: ThreadLocal tenant from a filter → Hibernate resolver →
connection provider does SET search_path per checkout, reset on release.
</details>

<details><summary><b>Q5.</b> How does readOnly=true route to a replica, and what's the mandatory caveat?</summary>

AbstractRoutingDataSource picks its target by
TransactionSynchronizationManager.isCurrentTransactionReadOnly() at connection
acquisition. Caveat: replication lag — read-your-own-writes must go to
primary or be served from the write's return value.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> In EXPLAIN ANALYZE output, what do you conclude from: Seq Scan; actual rows ≫ estimated rows; Index Only Scan?</summary>

Seq Scan on a selective filter: no usable index (or the planner judged the
table too small to bother). actual≫estimated: stale planner statistics — run
ANALYZE, then re-judge. Index Only Scan: covering index working as designed —
the best case.
</details>

<details><summary><b>Q7.</b> Why must the tenant connection provider reset search_path on release?</summary>

The pool hands the same physical connection to the next borrower; a lingering
search_path silently reads/writes the previous tenant's schema — a
cross-tenant data leak with no exception. Pooled connections are shared
mutable state; decorations must be undone.
</details>

<details><summary><b>Q8.</b> Keyset vs offset pagination — mechanics, and what UI capability keyset sacrifices?</summary>

OFFSET n scans and discards n rows — cost grows linearly with depth. Keyset
seeks the index at WHERE key > last-seen — constant cost at any depth.
Sacrifice: no random page jumps (page 47 needs the page-46 boundary key) —
fine for infinite scroll, wrong for numbered page navigation.
</details>

<details><summary><b>Q9.</b> When is adding an index the wrong call?</summary>

Write-heavy tables where the read it serves is rare (every INSERT/UPDATE pays
maintenance); low-selectivity columns (boolean-ish) where the planner
prefers a scan anyway; duplicated/unused indexes (idx_scan = 0) — pure write
tax; and as a substitute for fixing the query shape (functions on columns,
missing keyset).
</details>

---

## 🃏 FLASHCARDS

```
Migration naming	V{n}__{description}.sql (double underscore); R__ = repeatable on checksum change
Applied migration edited	Checksum mismatch → startup fails; fix-forward with a new version
Flyway + Hibernate setting	ddl-auto: none — Flyway is the only schema owner
flyway:clean	Drops everything — disable in prod
Left-prefix rule	Compound (a,b) serves a and a+b — never b alone
Partial index	Index WHERE status='PENDING' — index the hot subset only
Covering index	INCLUDE(cols) → Index Only Scan, no heap visit
Function on indexed column	Kills the index — use an expression index on LOWER(col)
Deep pagination	Keyset WHERE id > :last (constant) beats OFFSET (linear); no page jumps
Multi-tenancy ladder	DB > schema > shared table — isolation down, cost down
search_path hygiene	Set per checkout, RESET on release — else cross-tenant leak
Replica routing key	isCurrentTransactionReadOnly() → readOnly tx → replica; lag → RYW to primary
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| readOnly routing payoff | class-level readOnly pattern | `01-foundations/05` |
| processed_events DDL | idempotency ledger | `04-kafka/02` |
| TenantContext ThreadLocal | MDC + its async caveat | `01-foundations/07` |
| EXPLAIN before indexing | statistics before optimizing | `01-foundations/06` |
| keyset pagination | Page/Slice economics | `01-foundations/04` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
