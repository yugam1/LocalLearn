# Transaction Management
**✅ Completed · plan: Phase 1, Task 5** — Propagation, isolation, optimistic/pessimistic locking, rollback rules

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** `@Transactional` is not magic on your method — it's a **proxy
wrapped around your bean**. The proxy borrows a connection from HikariCP, turns
off autocommit, calls your method, and at the outermost exit decides:
commit or rollback.

```
 caller ──▶ [ PROXY ]────────────────────────────┐
            │ 1. get connection, autocommit=off  │
            │ 2. ──▶ YOUR METHOD                 │
            │ 3. exit: normal → COMMIT           │
            │          RuntimeException → ROLLBACK│
            └─────────────────────────────────────┘
```

**Five rules you must never get wrong:**
1. `this.someTransactionalMethod()` **bypasses the proxy** — no transaction. The proxy is a bouncer at the front door; `this.` sneaks in the back.
2. Checked exceptions do **NOT** roll back by default. Spring rolls back on *surprises* (unchecked); checked exceptions are "part of the plan" → commit.
3. `REQUIRES_NEW` = separate transaction that **survives the caller's rollback** (audit logs).
4. Optimistic = **check at the exit** (`@Version` on UPDATE). Pessimistic = **guard at the entrance** (`FOR UPDATE` on SELECT).
5. Isolation ladder — each step up kills one anomaly, in order: **D**irty → **N**on-repeatable → **P**hantom.

---

## 🔮 PREDICT FIRST

*Answer each in your head BEFORE opening it. Being wrong here is the point —
a corrected prediction sticks 10× better than a read sentence.*

<details>
<summary><b>P1.</b> Same class: <code>createOrder()</code> (no annotation) calls <code>this.saveWithTx()</code> which has <code>@Transactional</code>. <code>saveWithTx</code> throws a RuntimeException halfway. What happens to the rows it inserted?</summary>

**They stay in the database.** `this.saveWithTx()` is a plain Java call — it never
went through the proxy, so `@Transactional` never activated. Each `save()` ran in
its own autocommit. There was no transaction to roll back.

Fix: move the method to another bean, or inject the bean into itself
(`ObjectProvider<OrderService>`), or restructure so the entry point is transactional.
</details>

<details>
<summary><b>P2.</b> A <code>@Transactional</code> method inserts an order, then throws <code>IOException</code> (checked, declared with <code>throws</code>). Committed or rolled back?</summary>

**Committed.** Default rollback rule = unchecked only. Spring's reasoning: a checked
exception is a declared, anticipated outcome — the caller may want the work kept.
To change it: `@Transactional(rollbackFor = Exception.class)`.
</details>

<details>
<summary><b>P3.</b> <code>createOrder()</code> saves the order, calls <code>auditService.log()</code> which is <code>REQUIRES_NEW</code>, then throws. Which rows survive: order, audit, both, neither?</summary>

**Only the audit row.** `REQUIRES_NEW` suspended the order transaction, opened its
own, and **committed before control returned**. When the parent later rolled back,
the audit commit was already durable. That's the entire reason the pattern exists.

(Follow-up you should ask yourself: what does it cost? A **second connection** from
the pool while the first is suspended — a deadlock risk when the pool is small.)
</details>

---

## 📖 THE STORY

### 1. Propagation = what happens at a method boundary

Only one question ever matters: *a transactional method calls another method —
whose transaction does the callee run in?* Every propagation value is one answer
to that. Think of the transaction as a **car ride**:

| Value | The car rule | Real use |
|---|---|---|
| `REQUIRED` *(default)* | hop into the caller's car; if nobody's driving, start one | 95% of code |
| `REQUIRES_NEW` | take **my own car**; caller's car waits in a lay-by | audit log that must survive |
| `NESTED` | same car, but I buckle my own seatbelt (savepoint — I can be ejected without crashing the car) | per-item rollback in a batch |
| `MANDATORY` | I only ride, never drive — error if no car | protect internal steps |
| `SUPPORTS` | car or walk, whatever's there | optional-tx reads |
| `NOT_SUPPORTED` | I insist on walking; park your car | long non-DB work mid-flow |
| `NEVER` | if I see a car, I panic (exception) | diagnostics endpoints |

The interview separator is `REQUIRES_NEW` vs `NESTED`:
- `REQUIRES_NEW` → **two independent commits**. Child survives parent rollback.
- `NESTED` → **one commit**, child is a savepoint. Parent rollback erases the child too; child rollback alone spares the parent.

### 2. Isolation = what leaks between concurrent transactions

Three anomalies, always in this order (**D → N → P**, weakest to hardest to prevent):

- **Dirty read** — I read data you haven't committed. You roll back. I acted on fiction.
- **Non-repeatable read** — I read the *same row* twice; you committed an update in between; my two reads disagree.
- **Phantom** — I ran the *same query* twice; you inserted a new matching row in between; a row appeared out of nowhere.

Each level up the ladder kills exactly one more:

```
SERIALIZABLE      kills phantoms        (and all below)   ← last ghost dies
REPEATABLE_READ   kills non-repeatable  (and dirty)
READ_COMMITTED    kills dirty reads                        ← PostgreSQL default
READ_UNCOMMITTED  kills nothing
```

Cost climbs with the ladder: SERIALIZABLE means contending transactions queue or
abort — reserve it for "last item in stock" moments, never as a default.

```java
@Transactional(isolation = Isolation.REPEATABLE_READ)   // both reads must agree
public BigDecimal calculateRevenue(LocalDate date) {
    BigDecimal before = orderRepo.sumByDate(date);
    doHeavyCalculation();
    return orderRepo.sumByDate(date);    // guaranteed == before
}
```

### 3. Two ways to fight over a row

Two users load order #42, both edit, both save. Who wins?

**Optimistic — apologize later.** `@Version Long version;` Every UPDATE becomes:

```sql
UPDATE orders SET ..., version = 2 WHERE id = 42 AND version = 1;
-- 0 rows matched → someone got there first → OptimisticLockException → tell user "refresh & retry" (HTTP 409)
```

No DB lock is ever held. Works across HTTP requests (version travels in the DTO).
This is **compare-and-swap at the database layer** — literally the same idea as
`AtomicInteger.compareAndSet` ([02-concurrency/03](../02-concurrency/03-atomicity-races-cas.md)).

**Pessimistic — ask permission first.**

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)   // SELECT ... FOR UPDATE
@Query("SELECT p FROM Product p WHERE p.id = :id")
Optional<Product> findByIdWithLock(Long id);
```

The row is locked from read until commit; everyone else **waits**. Right when
contention is high and the transaction is short (inventory decrement, seat allocation).
Variants: `PESSIMISTIC_READ` (shared — readers OK, writers blocked),
`PESSIMISTIC_WRITE` (exclusive), `PESSIMISTIC_FORCE_INCREMENT` (exclusive + bumps version).

> **Choosing:** conflicts rare → optimistic (pay nothing usually).
> Conflicts common + tx short → pessimistic (waiting beats retry storms).
> Same trade-off as CAS vs lock in the JVM — one layer down.

### 4. Rollback mechanics worth knowing

```java
@Transactional(rollbackFor = Exception.class,      // include checked
               noRollbackFor = WarningException.class)

// roll back without throwing:
TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
```

And the silent killer: a `REQUIRED` inner method catches an exception, but the
transaction was already **marked rollback-only** when the exception crossed the
inner proxy → outer commit fails with `UnexpectedRollbackException`. You can't
"catch your way out" of a shared transaction.

### 5. `readOnly = true` and short transactions

Class-level `@Transactional(readOnly = true)`, override per write method.
Read-only skips Hibernate dirty-checking/flush, documents intent, and can route
to read replicas.

Keep transactions **short**: a transaction holds a pool connection + row locks
the entire time. An HTTP/email call inside one is how you exhaust HikariCP.
Side effects go to Kafka/async **after** (or via outbox), never inline:

```java
// ❌ holds a DB connection for the duration of an SMTP round trip
@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    emailService.sendConfirmationEmail(order);       // seconds!
    return mapToResponse(order);
}
// ✅ persist, publish event, return — side effects happen async
```

---

## 🎯 RETRIEVAL GYM

*Closed book. Say the answer out loud, THEN open. If you miss one, reread only
that section of the Story, not the whole doc.*

**Tier 1 — must be automatic (interview: instant answers)**

<details><summary><b>Q1.</b> REQUIRED vs REQUIRES_NEW — one sentence each + the classic use case for the second.</summary>

REQUIRED joins the caller's transaction or starts one (one commit, all-or-nothing).
REQUIRES_NEW suspends the caller and runs its own transaction that commits
independently — used for audit logs that must persist even when the business
operation rolls back. Cost: a second pool connection while the first sits suspended.
</details>

<details><summary><b>Q2.</b> Why did my @Transactional not roll back on an IOException?</summary>

Default rollback triggers on unchecked exceptions only; checked exceptions are
treated as anticipated outcomes and commit. Fix: `rollbackFor = Exception.class`.
</details>

<details><summary><b>Q3.</b> Name the three read anomalies in order and the cheapest isolation level that prevents each.</summary>

Dirty read → READ_COMMITTED. Non-repeatable read → REPEATABLE_READ.
Phantom → SERIALIZABLE. (Ladder: each step up kills exactly one more.)
</details>

<details><summary><b>Q4.</b> Optimistic vs pessimistic locking — mechanism, failure mode, when to pick which?</summary>

Optimistic: `@Version` column checked in the UPDATE's WHERE clause; conflict
surfaces at commit as OptimisticLockException; no lock held; pick when conflicts
are rare or the "transaction" spans HTTP requests. Pessimistic: `SELECT FOR UPDATE`
locks the row at read; others block until commit; pick for hot rows with short
transactions. Optimistic = check at the exit; pessimistic = guard at the entrance.
</details>

<details><summary><b>Q5.</b> Why does calling a @Transactional method from the same class not open a transaction?</summary>

Spring implements @Transactional with a proxy around the bean; only calls that
enter *through the proxy* are intercepted. `this.method()` is a direct Java call —
back door past the bouncer. Fix: separate bean, self-injection, or make the
public entry point transactional.
</details>

**Tier 2 — depth (senior/6-yr differentiators)**

<details><summary><b>Q6.</b> NESTED vs REQUIRES_NEW — what actually differs at commit time?</summary>

NESTED is a savepoint inside the SAME physical transaction: one final commit;
parent rollback wipes the child; child rollback alone rolls to the savepoint.
REQUIRES_NEW is a second physical transaction with its own commit that survives
the parent. Also: NESTED needs savepoint support (JDBC), doesn't consume a
second connection.
</details>

<details><summary><b>Q7.</b> What is UnexpectedRollbackException and what design mistake produces it?</summary>

An inner REQUIRED method threw; the shared transaction got marked rollback-only
as the exception crossed the inner proxy; an outer method caught the exception
and tried to commit anyway. You can't catch your way out of a shared transaction —
either use REQUIRES_NEW/NESTED for the fallible part, or let it propagate.
</details>

<details><summary><b>Q8.</b> A transaction takes 8 seconds because it calls a payment API inline. Name three distinct production problems this causes.</summary>

(1) Pool exhaustion — the HikariCP connection is held for 8s, so ~pool-size
concurrent requests freeze the whole app. (2) Lock hold time — any row locks
block other transactions for 8s → timeouts/deadlocks. (3) Consistency trap —
if the API succeeds but the tx rolls back, you've charged without an order
(hence outbox pattern / publish-after-commit).
</details>

<details><summary><b>Q9.</b> How is @Version the same idea as compareAndSet?</summary>

Both are optimistic concurrency: read a value + token, do work, then atomically
"write only if the token is unchanged" — CAS does it on one memory word,
`@Version` does it via `WHERE version = ?` on one row. Both fail-and-retry
instead of blocking, and both suffer under heavy contention.
</details>

---

## 🃏 FLASHCARDS

```
this.txMethod() — in a transaction?	No — direct call bypasses the proxy (the bouncer's back door)
Default rollback trigger	Unchecked only; checked commits (rollbackFor=Exception.class to change)
Propagation for "audit must survive rollback"	REQUIRES_NEW (own physical tx, commits independently, costs 2nd connection)
Anomaly order (weak→strong)	Dirty → Non-repeatable → Phantom (each isolation step kills one)
PostgreSQL default isolation	READ_COMMITTED
@Version conflict → exception + HTTP code	OptimisticLockException → 409 Conflict, "refresh & retry"
PESSIMISTIC_WRITE emits what SQL	SELECT ... FOR UPDATE (row locked until commit, others wait)
NESTED = ?	Savepoint in the SAME tx: one commit; parent rollback wipes child
readOnly=true buys you	No dirty-check/flush, intent documentation, replica routing
Why keep transactions short	Holds pool connection + row locks the whole time; no HTTP/email inside
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Is the same idea as | Where |
|---|---|---|
| `@Version` optimistic locking | CAS / `compareAndSet`, incl. ABA→stamp | `02-concurrency/03` |
| `PESSIMISTIC_WRITE` blocking | `synchronized` / lock parking trade-off | `02-concurrency/04` |
| Isolation levels | visibility between "threads" (transactions) | `02-concurrency/02` |
| Publish-after-commit / outbox | Kafka producer + exactly-once concerns | `04-kafka/02`, task 12 |

*Spotting these mappings IS the retention trick: one mental model, four contexts —
each context is a retrieval cue for the others.*

---

## 🗓 REVISION LOG

Do only ⚡ Core Card + 🎯 Gym on each pass (~6 min). Reread 📖 Story sections only
for questions you miss.

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (do the gym of 2–3 OTHER topics in the same sitting — interleaving)

Miss ≥2 Tier-1 questions on any pass → reset the schedule to R1.
