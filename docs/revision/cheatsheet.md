# ⚡ The One-Page Brain — All Core Cards

> Every topic's 60-second Core Card, in curriculum order. Skim the whole
> curriculum in ~25 minutes; skim your weak areas before an interview in 5.
> Deep dive / retrieval practice: open the linked doc.

## 01.1 · REST API, DI & Layers
*Full doc: [`../01-foundations/01-di-ioc-rest-layers.md`](../01-foundations/01-di-ioc-rest-layers.md)*

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

## 01.2 · Exceptions & Validation
*Full doc: [`../01-foundations/02-exceptions-validation.md`](../01-foundations/02-exceptions-validation.md)*

**Mental model:** Exceptions are **part of your API contract**. Controllers stay
clean because every exception — yours or Spring's — funnels into **one**
`@RestControllerAdvice` that maps *exception type → HTTP status → one standard
error JSON*. Validation is declarative: constraints live on the DTO, `@Valid`
triggers them, and a failure is just another exception arriving at the same funnel.

```
JSON in ─▶ deserialize ─▶ @Valid? ──fail──▶ MethodArgumentNotValidException ─┐
                              │ pass                                          │
                              ▼                                               ▼
                        controller ─▶ service ──throws──▶ @RestControllerAdvice
                                                          type → status → ErrorResponse
```

**Five rules you must never get wrong:**
1. Business exceptions extend **RuntimeException** — unchecked bubbles freely to the advice, no `throws` litter.
2. `@Valid` on the `@RequestBody` or constraints are **silently ignored**. Same for nested objects/lists — `@Valid` doesn't cascade on its own.
3. `@NotNull` < `@NotEmpty` < `@NotBlank` (strictest for strings — rejects `" "`).
4. **400** = malformed request · **422** = well-formed but business says no · **409** = concurrent conflict.
5. **Never** leak stack traces or class names to clients — log the trace, return a generic message.

---

## 01.3 · JPA Entities & HikariCP
*Full doc: [`../01-foundations/03-jpa-entities-relationships.md`](../01-foundations/03-jpa-entities-relationships.md)*

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

## 01.4 · Specifications & Projections
*Full doc: [`../01-foundations/04-repositories-queries-pagination.md`](../01-foundations/04-repositories-queries-pagination.md)*

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

## 01.5 · Transactions
*Full doc: [`../01-foundations/05-transactions-isolation-locking.md`](../01-foundations/05-transactions-isolation-locking.md)*

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

## 01.6 · N+1 & Performance
*Full doc: [`../01-foundations/06-n-plus-one-hikaricp-tuning.md`](../01-foundations/06-n-plus-one-hikaricp-tuning.md)*

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

## 01.7 · Logging, MDC & Correlation IDs
*Full doc: [`../01-foundations/07-logging-mdc-correlation-ids.md`](../01-foundations/07-logging-mdc-correlation-ids.md)*

**Mental model:** In production you don't debug — **you grep**. Logging is
designing, in advance, the query you'll run at 3 a.m. The unit of that query is
the **correlation ID**: one ID stamped on a request at the front door (MDC
filter), carried through every thread, service, and Kafka hop, so
`grep REQ-abc-123 *.log` reconstructs the whole story.

```
Client → [MDCFilter: put correlationId] → controller → service → @Async ──MDC copied──▶ worker
   │                                                    │
   └── header X-Correlation-ID out                      └── Kafka event.correlationId ──▶ consumer restores MDC
```

**Five rules you must never get wrong:**
1. **MDC is thread-local** — new threads start EMPTY. `@Async`/executors need a `TaskDecorator` that copies + restores + clears the context map.
2. `MDC.clear()` in `finally`, always — pooled threads otherwise leak one request's context into the next.
3. **Parameterized logging only**: `log.info("id={}", id)` — concatenation pays the string cost even when the level is off. Exception goes **last**: `log.error("failed: id={}", id, ex)`.
4. Levels: INFO = business events, WARN = handled anomalies (reviewed), ERROR = needs attention (alerts fire on it).
5. Never log secrets: passwords, tokens, full card numbers — mask (`j***n@`, `last4`).

---

## 02.1 · Threads & Interruption
*Full doc: [`../02-concurrency/01-threads-lifecycle-interruption.md`](../02-concurrency/01-threads-lifecycle-interruption.md)*

**Mental model:** A thread is an expensive OS resource (~1MB, ~1ms) that you can
only ever **ask** to stop. Everything in this topic follows from two facts:
exceptions and interrupts don't cross thread boundaries by magic, and
cancellation is a *cooperative protocol* — flag set by one side, honored by the
other.

```java
// the canonical cancellable worker — all three parts, every time
while (!Thread.currentThread().isInterrupted()) { ... }   // 1. poll
catch (InterruptedException e) { Thread.currentThread().interrupt(); }  // 2. restore
finally { cleanup(); }                                     // 3. always
```

**Five rules you must never get wrong:**
1. `interrupt()` sets a flag — that's ALL. Blocking calls throw (and **clear the flag**); CPU loops must poll.
2. Every `catch (InterruptedException)` → rethrow **or** `Thread.currentThread().interrupt()`. No third option.
3. `RUNNABLE` in a dump is a liar — it includes threads blocked on I/O. `BLOCKED` means monitors ONLY; a ReentrantLock deadlock shows `WAITING`.
4. Daemon threads die mid-instruction at JVM exit — no finally, no flush. Never for cleanup/I/O.
5. A worker's exception goes to its `UncaughtExceptionHandler` — in a pool it hides in the Future until someone calls `get()`.

*Run the demos before reading: D1 (states), D2 (join/daemon/exceptions), D3 (interruption — the non-polling thread never stops).*

---

## 02.2 · Java Memory Model
*Full doc: [`../02-concurrency/02-jmm-visibility-happens-before.md`](../02-concurrency/02-jmm-visibility-happens-before.md)*

**Mental model:** The JMM is a **contract of edges, not a promise of freshness**.
Threads see each other's writes ONLY across a *happens-before* edge (volatile,
monitor, start/join, final-freeze). No edge → the compiler may hoist your read
out of the loop and the CPU may reorder your stores — "guaranteed nothing," not
"usually fine." And the villain is the **JIT**, not CPU caches.

```
writer:  config = load();  ready = true;   ← volatile write = RELEASE
reader:  if (ready)        use(config);    ← volatile read  = ACQUIRE
         everything before the release is visible after the acquire
```

**Five rules you must never get wrong:**
1. `volatile` = access atomicity + visibility (no hoisting) + **ordering** (release/acquire). It never makes `x++` atomic.
2. The stale-flag hang is the JIT hoisting the read (`while(!stop)` → `if(!stop) while(true)`); `-Xint` proves it.
3. A lock is a memory barrier, not just mutual exclusion — **readers need the edge too**.
4. `ref = new Config(42)` is allocate→construct→publish, and construct/publish can reorder → half-built object. Hence volatile DCL, and why `final` fields publish safely for free.
5. "Works on my x86" ≠ correct: x86 is TSO-strong; ARM will collect on missing edges. **You cannot test your way to memory-model correctness.**

*Run first: D4 (flag never stops), D5 (92% reordering anomalies), D6 (safe publication + the HashMap that hangs forever).*

---

## 02.3 · Races, Atomicity & CAS
*Full doc: [`../02-concurrency/03-atomicity-races-cas.md`](../02-concurrency/03-atomicity-races-cas.md)*

**Mental model:** Atomicity bugs live **in the gap between steps** of things
that look like one step. `count++` is read-modify-write (three bytecodes);
`if (stock > 0) stock--` is check-then-act. Making the *pieces* atomic doesn't
close the gap — it only makes the bug rarer, which is worse. Either the whole
compound action is one indivisible step (CAS loop, lock, or a DB
`UPDATE...WHERE`), or it's broken.

```
volatile int counter, 8×100k increments:  lost 72%   ← volatile is NOT a fix
"atomic decrement only" half-fix:         oversells 2% of trials — rarer, still broken
CAS retry loop / synchronized:            correct
```

**Five rules you must never get wrong:**
1. `volatile` = visibility + ordering. **Never** atomicity of `x++`.
2. "It happens less often now" is not a fix — rare bugs survive test suites and ship.
3. CAS = "set only if still equals expected," one CPU instruction; every `Atomic*` mutator is a retry loop over it. Lock-free, deadlock-free, retry-storms under heavy contention.
4. ABA: CAS compares values, not history — fix with `AtomicStampedReference` (a version stamp), which is exactly JPA `@Version`.
5. Invariant spans >1 variable or >1 JVM? CAS can't help — lock (in-process) or push the invariant into the database.

*Run first: D7 (lost updates), D8 (overselling + the half-fix), D9 (LongAdder vs AtomicLong).*

---

## 02.4 · Locks & Deadlock
*Full doc: [`../02-concurrency/04-locks-deadlock-conditions.md`](../02-concurrency/04-locks-deadlock-conditions.md)*

**Mental model:** A lock buys you two things at once — exclusion AND a memory
edge — and costs you a new failure mode: **deadlock, which is never "two locks"
but "two locks in two different orders."** Every fix breaks one of Coffman's
four conditions; lock ordering (acquire by ascending id) breaks circular wait
and is the fix you reach for first.

```
deadlock: T1 holds A wants B │ T2 holds B wants A     → dump shows it (jcmd Thread.print)
livelock: both RUNNABLE, retrying in lockstep forever → dump looks FINE (needs jitter)
```

**Five rules you must never get wrong:**
1. Default to `synchronized` (can't leak the lock); reach for `ReentrantLock` only for tryLock / lockInterruptibly / fairness / multiple Conditions — and then `unlock()` in `finally`, always.
2. Fairness costs ~92× (measured) — every handoff becomes a park/unpark syscall. Only for demonstrated starvation.
3. `ReadWriteLock` pays off only when reads dominate **and** hold the lock >~1µs (at 0ns it LOSES even at 100% reads). Map-shaped data → `ConcurrentHashMap` instead.
4. Always `while (!condition) cond.await()` — never `if` (spurious wakeups, signalAll, signal-steal: three independent reasons).
5. Cache stampede → `computeIfAbsent` (per-bin lock: one loader per key, parallel across keys); slow loaders → cache a `CompletableFuture`.

*Run first: D10 (fair vs non-fair), D11 (real deadlock, detected), D12 (RWLock economics + stampede).*

---

## 02.5 · Hand-off, Blocking Queues & Backpressure
*Full doc: [`../02-concurrency/05-handoff-blocking-queues.md`](../02-concurrency/05-handoff-blocking-queues.md)*

**Mental model:** A queue between a producer and a consumer does not make them
the same speed — it only decides **what happens when they aren't**. Exactly three
answers exist and there is no fourth: **block** (backpressure), **drop** (shed),
or **grow** (crash later, while feeling like you chose nothing). An unbounded
queue is not a buffer; it is an `OutOfMemoryError` with a delay fuse.

```
UNBOUNDED  produced 1,007,114 | consumed 7,116 | backlog 1,000,000 in 419ms (~140 MB)
BOUNDED    produced    52,958 | consumed 51,934 | backlog     1,024        (~144 KB)
```

**Five rules you must never get wrong:**
1. **Bounded unless you can prove the producer is rate-limited.** Capacity is not a tuning knob — it is *where your system fails*, chosen on purpose.
2. `poll()` = null now (a loop on it is a busy-wait, one burned core per idle worker) · `take()` = parks · **`poll(timeout)` = what production loops want** (parks, but wakes for shutdown checks).
3. A `volatile` flag **cannot stop a thread parked in `take()`**. Visible ≠ awake — a parked thread executes nothing, so it re-checks nothing. (Not D4's bug: that was the read being optimised away.)
4. Shutdown = **drain** (poison pill / `shutdown()`) or **abandon** (interrupt / `shutdownNow()`). Pick deliberately; the default is silent data loss. Pill compared with `==`, N consumers need N pills.
5. `SynchronousQueue` capacity is **zero** — a rendezvous whose job is making "no idle consumer" an instant signal. That is how `newCachedThreadPool` decides to spawn.

*Run first: D13 (140 MB backlog in 0.4s), D14 (the family + a benchmark that contradicts intuition), D15 (three shutdowns, two broken).*

---

## 02.6 · Shared Structures: ConcurrentHashMap, CopyOnWrite, ThreadLocal
*Full doc: [`../02-concurrency/06-shared-structures.md`](../02-concurrency/06-shared-structures.md)*

**Mental model:** A thread-safe collection makes **each call** atomic. It cannot
make **your sequence of calls** atomic, because it has no idea which of your
calls belong together. So `ConcurrentHashMap` fixes the corruption and leaves
the race — the same check-then-act bug as D8 and Ex6, third outfit. There are
exactly three answers to "two threads touch this": **coordinate** it (topics
3–4), **copy** it (`CopyOnWriteArrayList`), or **confine** it (`ThreadLocal`).
The last two look free and are not.

```
HashMap, 8 threads x 5,000 distinct keys (expect 40,000):
    lost 5.3%–31.9%  ·  or a worker never returns from put()  ·  or ClassCastException
ConcurrentHashMap, map.put(k, map.get(k)+1):     still loses 0.7%–13.3%
ConcurrentHashMap, map.merge(k, 1L, Long::sum):  exact, every trial
                                                 ↑ the map never changed. The CALL did.
```

**The decision rule:** **if the new value depends on the old one, it must be one
call** — `merge`, `compute`, `computeIfAbsent`, `putIfAbsent`, `replace(k, old,
new)`. Two calls is a race no matter how thread-safe each one is.

**Five rules you must never get wrong:**
1. `containsKey`-then-`put` and `get`-then-`put` are **check-then-act** — same bug as D8's overselling and Ex6's stampede. Measured: 11–15% of 200 trials elected more than one "first" leader.
2. The mapping function runs **under the bin lock**. Keep it short, never touch the same map inside it, and cache a `CompletableFuture` instead of the value for a slow load.
3. `size()` on a busy `ConcurrentHashMap` is an **estimate**. Fine for a dashboard; `if (map.size() < CAP) put(...)` is D8 with a lie in the check.
4. `CopyOnWriteArrayList` costs **O(n) per write**, so building one is O(n²) — measured 1.1 µs/element at 4,000 → 19.6 µs/element at 64,000. Right for listener lists, catastrophic for anything that accumulates.
5. A `ThreadLocal` value lives as long as the **thread**, and a pooled thread outlives your request on purpose. `remove()` in a `finally` — and `remove()`, never `set(null)`, which leaves the entry behind.

*Run first: D16 (three different ways a HashMap breaks), D17 (the same old bug on a thread-safe map, and per-bin locking measured), D18 (copy-on-write's real bill, and a tenant id leaking into the next request).*

---

## 02.7 · Coordination: Latch, Barrier, Semaphore, Phaser
*Full doc: [`../02-concurrency/07-coordination.md`](../02-concurrency/07-coordination.md)*

**Mental model:** These are not four APIs. They are **one 2×2**, and the two
questions are *is it reusable?* and *am I waiting for events to happen, or for
parties to arrive?* Get the axes and you never have to memorise a method list
again.

```
                 │ waiting for EVENTS to happen  │ waiting for PARTIES to arrive
 ────────────────┼───────────────────────────────┼──────────────────────────────
  ONE-SHOT       │ CountDownLatch                │ (a latch used as a start gate)
  REUSABLE       │ Semaphore — permits, D20      │ CyclicBarrier · Phaser
```

**The decision rule:** **does it have to happen more than once?** If yes, you
cannot use a latch — no amount of care makes a one-shot counter reusable, and
the failure is silent.

**Five rules you must never get wrong:**
1. A latch **counts down to zero and stays there**. `countDown()` on zero is a no-op; `await()` on zero returns instantly. Reuse it across rounds and round 2 has **no barrier at all** — measured: 6 workers in round 1, then 1, 1, 1, 1.
2. `countDown()` and `release()` go in a **`finally`**. The commonest cause of a production hang is not a cycle; it is one worker that threw on the way to its `countDown()`.
3. A `Semaphore` counts **permits to use a resource**, not events. Permits are anonymous: any thread may release, `acquire()` is **not reentrant**, and `release()` without `acquire()` mints a permit out of nothing.
4. A leaked permit is **permanent and cumulative**. Measured: 4 exceptions destroyed a 4-permit pool, and it never recovered.
5. **A latch or semaphore deadlock produces no deadlock report.** Detection needs an *owner* to draw an edge to, and permits and latch counts deliberately have none. Use `await(timeout)` / `tryAcquire(timeout)`, and gauge `getCount()` / `availablePermits()`.

*Run first: D19 (the 2×2, and a latch silently ceasing to synchronise), D20 (permits, and a pool dying one exception at a time), D21 (three deadlocks, one diagnosed).*

---

## 02.8 · ThreadPoolExecutor Internals
*Full doc: [`../02-concurrency/08-threadpool-internals.md`](../02-concurrency/08-threadpool-internals.md)*

**Mental model:** A thread pool has four numbers and they are **not** consulted
in the order you read them. For every submitted task the pool asks: *below core?
→ start a thread. Otherwise, **will the queue take it?** → queue it. Only if the
queue **refuses** → grow toward max. Only if that fails too → reject.* The queue
is tried **before** the pool is allowed to grow, because a queue slot is a
pointer and a thread is a megabyte. Every surprising thing a pool does follows
from that one inversion.

```
task → workers < core?     → NEW THREAD
     → queue.offer(task)?  → QUEUED            ← tried BEFORE growing
     → workers < max?      → NEW THREAD
     → else                → RejectedExecutionHandler

measured (D22, core=2 max=10 queue=100, 60 simultaneous tasks):
    poolSize=2   active=2   queued=58     ← 8 permitted threads never created
same pool, queue=4, the ONLY change:
    poolSize=10  queued=4   accepted=14   rejected=46
```

**The decision rule:** **size the queue first, because the queue is what decides
whether `maxPoolSize` exists at all.** A large queue buys latency tolerance and
makes max unreachable; a small queue buys concurrency and makes rejection real.
You cannot have both, and picking neither gives you the first one by accident.

**Five rules you must never get wrong:**
1. **Queue before growth.** With a large or unbounded queue, `maxPoolSize` is not a safety limit — it is unreachable code. You would need 103 concurrent tasks to create thread #3 above.
2. `newFixedThreadPool` and `newSingleThreadExecutor` have an **unbounded** queue: they can never reject, so they fail as heap instead. Measured: 1,000,000 tasks queued in 104 ms behind 2 workers, nothing refused.
3. `newCachedThreadPool` has **unbounded threads** — a `SynchronousQueue` (capacity zero, topic 5) guarantees the queue always refuses, so every task with no idle worker creates one. Measured: 1,000 tasks → 1,000 threads.
4. Sizing is one question, not two formulas: **what fraction of the task actually holds a core?** CPU-bound ≈ cores; IO-bound ≈ cores × (1 + wait/compute). Measured cost of confusing them: **12.5× vs 42×**.
5. `submit()` **swallows** the exception into a `Future` and tells nobody; `execute()` lets it reach the `UncaughtExceptionHandler` and kills the worker. Fire-and-forget `submit()` is a failure detector with the detector removed.

*Run first: D22 (the staircase, and two `Executors` factories failing), D23 (four
rejection policies, and the fastest producer is the one that lost everything),
D24 (two sizing sweeps, three shutdowns, one vanishing exception).*

---

## 02.9 · ForkJoin, Parallel Streams & Virtual Threads
*Full doc: [`../02-concurrency/09-forkjoin-parallel-virtual-threads.md`](../02-concurrency/09-forkjoin-parallel-virtual-threads.md)*

**Mental model:** This entire topic is one question asked before you choose
anything: **what kind of work is this?** There are three answers, each with a
different right tool, and every mistake on this page is the answer to one
question being applied to another.

```
                                  what kind of work is this?
                                              |
   CPU-bound, splits recursively  ────────────┼──── ForkJoinPool / parallel stream
   (sum a tree, sort, scan)                   |     work-stealing deques, cores-1
                                              |
   CPU-bound, independent tasks   ────────────┼──── bounded platform pool (topic 8)
   (render N images)                          |     because cores are the scarce thing
                                              |
   IO-bound, lots of it           ────────────┴──── ONE VIRTUAL THREAD PER TASK
   (call 10,000 services)                           a blocked one holds no OS thread

measured (D27 §2, 10,000 tasks each blocked 100ms):
    platform pool of 12      115 tasks/sec        ← topic 8's whole sizing problem
    platform pool of 1000  8,482 tasks/sec
    virtual thread per task 63,694 tasks/sec      ← ~550x the cores-sized pool
```

**The decision rule:** **if it blocks, it is not CPU work — get it off the
ForkJoin pools entirely.** Blocking inside a parallel stream starves a JVM-wide
resource; blocking inside `synchronized` on a virtual thread pins a carrier.
Both are measured below, and both are invisible in the source code.

**Five rules you must never get wrong:**
1. `fork()` immediately followed by `join()` on the same task **runs the halves sequentially**. Measured: 0.5–0.7× — *slower than the plain loop it replaced*, in 6 of 6 runs. The rule is `right.fork(); left.compute(); right.join();`.
2. `parallelStream()` runs on `ForkJoinPool.commonPool()` — **one pool for the entire JVM**, `cores - 1` workers, shared with every library you depend on and with any `CompletableFuture` that omits an executor. There is no bulkhead.
3. Blocking in a parallel stream is a **process-wide** slowdown, not a local one. Measured: an unrelated CPU-only parallel stream went **7.5× slower** while 44 tasks blocked elsewhere in the JVM.
4. Virtual threads make **blocking** cheap, not computing fast. ~2.3 µs to create one versus ~120 µs for a platform thread, and a blocked one consumes no OS thread at all.
5. On Java 21, `synchronized` **pins** a virtual thread to its carrier; `ReentrantLock` does not. Measured with **zero contention**: 110 ms → 14,400–17,400 ms. **Never hold a monitor across a blocking call.**

*Run first: D27 (the 550× scale difference, then the pinning collapse — the most
important measurement on this page), D26 (common-pool starvation), D25 (the
ordering rule), D28 (what structured concurrency is for).*

---

## 02.10 · Diagnostics & The Capstone Incident
*Full doc: [`../02-concurrency/10-diagnostics-incident.md`](../02-concurrency/10-diagnostics-incident.md)*

**Mental model:** Every other page in this curriculum announces its mechanism in
the title, so you always know which chapter the fix comes from. Production never
does that. It hands you a **symptom** — "it's hung", "a core is pinned", "the
numbers are wrong" — and the entire skill is getting from there to a mechanism.
That skill is not a fifth thing after the four you already know; it is the
ability to look at *where a thread stopped* and recognise which of topics 1–9
puts a thread there.

The instruments are few, they are all built into the JDK, and each has a blind
spot you must memorise, because the blind spots are where the expensive
incidents live.

```
                    finds it?   findDeadlockedThreads()  findMonitorDeadlocked()
synchronized cycle      yes              2 threads              2 threads
ReentrantLock cycle     yes              4 threads              2 threads   <- diverges
Semaphore permit cycle  NO               4 threads              2 threads   <- unchanged
                                         ^ two threads parked forever, and the
                                           count did not move. Measured in D29.
```

**The decision rule:** **`findDeadlockedThreads()` returning `null` narrows the
cause; it never clears it.** It means "not a cycle of lock *ownership*", which
rules out one family and leaves three — exhausted pool, uncounted latch, unfed
queue — each of which is diagnosed from thread *stacks* and your own gauges
instead.

**Five rules you must never get wrong:**
1. `RUNNABLE` is a liar in both directions: it includes threads blocked in a native socket read, and it is also what a thread pinning a whole core looks like. Read the frames and the `cpu=` field, never the state word.
2. `BLOCKED` means an intrinsic `synchronized` monitor and nothing else. A `ReentrantLock` deadlock reports `WAITING`. Grep a dump for `BLOCKED`, find none, and you have ruled out nothing.
3. Automatic detection follows lock **ownership**. A `Semaphore` permit, a `CountDownLatch`, a `Future`, and an empty queue have no owner, so a cycle built from them hangs silently and forever.
4. Never block a pool thread on work that can only be performed by that same pool. With an unbounded queue nothing is rejected and nothing throws — the service just stops, permanently, and reports nothing.
5. A thread dump cannot see wrong data. A `ThreadLocal` left on a pooled thread produces a perfectly healthy JVM that answers for the wrong customer, and the only instrument that finds it is an assertion you wrote in advance.

*Run first: D29 (the instruments, calibrated against threads whose state you
chose), then D30 (four symptoms, no labels — diagnose them yourself), and only
then D31 (the answer key).*

---

## 03.1 · Thread Pools & @Async
*Full doc: [`../03-async-and-scheduling/01-thread-pools-completablefuture.md`](../03-async-and-scheduling/01-thread-pools-completablefuture.md)*

**Mental model:** A thread pool is a **restaurant kitchen**: core threads =
permanent cooks, queue = the order rail, max threads = temp cooks called in
*only when the rail is full*, rejection policy = what the maître d' does when
even that fails. Every production incident with pools is one of these four
numbers being wrong for the workload.

```
task → cooks free (< core)?  → run
     → rail has space?       → queue                ← NOTE: queue BEFORE growing!
     → can hire temps (< max)? → new thread
     → else                  → REJECTION POLICY
```

**Five rules you must never get wrong:**
1. **Queue fills BEFORE the pool grows past core.** maxPoolSize is meaningless with a huge/unbounded queue — it never triggers.
2. Sizing: CPU-bound = `cores + 1`; I/O-bound = `cores × (1 + wait/cpu)`. Queue = `peak_RPS × avg_duration_s × safety`.
3. Never `Executors.newFixedThreadPool` (unbounded queue → OOM) or `newCachedThreadPool` (unbounded threads) in prod — configure `ThreadPoolTaskExecutor` with a **bounded** queue.
4. `CallerRunsPolicy` = production default: the submitter runs the task itself → natural backpressure, nothing lost.
5. `@Async` = proxy again: public method, called **from another bean**, return `void` or `CompletableFuture` — and void methods **swallow exceptions** unless you configure `AsyncUncaughtExceptionHandler`.

---

## 03.2 · @Scheduled & ShedLock
*Full doc: [`../03-async-and-scheduling/02-scheduling-shedlock.md`](../03-async-and-scheduling/02-scheduling-shedlock.md)*

**Mental model:** `@Scheduled` is a **metronome with one hand** — by default a
SINGLE thread plays every scheduled task in the whole app. Two questions decide
everything: *when does the clock start counting* (rate = from previous START,
delay = from previous END), and *how many instances are ticking* (3 pods = 3
metronomes → duplicate emails, unless a distributed lock says only one plays).

```
fixedRate=10s,  task 2s:  |██|........|██|........      ticks at 0,10,20 (start→start)
fixedDelay=10s, task 3s:  |███|..........|███|......    ticks at 0,13,26 (end→start)
fixedRate=5s,   task 7s:  |███████|███████|             next fires IMMEDIATELY — overlap pressure
```

**Five rules you must never get wrong:**
1. **Default scheduler = 1 thread.** One slow task delays every other schedule. Always configure `ThreadPoolTaskScheduler` (pool ~10, named threads).
2. `fixedDelay` when runs must never overlap (cleanup, sync); `fixedRate` for time-critical ticks (heartbeat, polling).
3. **Wrap the whole task body in try-catch.** An escaping exception kills that run — and you must never gamble on whether future runs survive.
4. N instances = N executions. **ShedLock** (`@SchedulerLock` + DB lock row) makes exactly one instance run it.
5. Cron = **6 fields with seconds**: `sec min hour dom month dow` — `0 0 2 * * ?` = daily 02:00. `?` = "no specific value" for the dom/dow pair.

---

## 04.1 · Kafka Basics
*Full doc: [`../04-kafka/01-fundamentals-partitions-groups.md`](../04-kafka/01-fundamentals-partitions-groups.md)*

**Mental model:** Kafka is a **durable, replayable log**, not a queue. Messages
aren't consumed away — they sit on disk with retention; each consumer *group*
just remembers a bookmark (**offset**) per partition. Ordering exists only
**within a partition**, and the message **key** decides the partition — so
"events about the same order stay ordered" costs exactly one decision:
key = orderId.

```
topic order.created (3 partitions)
 P0: [e1][e4][e7]  ← ordered           group "order-service": A→P0, B→P1, C→P2 (work sharing)
 P1: [e2][e5]      ← ordered           group "analytics":     D→P0,P1,P2      (independent copy)
 P2: [e3][e6]      ← ordered
 cross-partition order: NONE           each group keeps its own offsets
```

**Five rules you must never get wrong:**
1. **Partition = ordering unit AND parallelism unit.** One partition ↔ at most one consumer *per group*; consumers beyond partition count sit idle.
2. Same key → same partition → ordered. No key → round-robin/sticky, no cross-event order.
3. Reliability trio for critical events: producer `acks=all` + `enable-idempotence=true`; consumer `enable-auto-commit=false` + manual `ack.acknowledge()` after processing.
4. Commit-after-process = **at-least-once** → duplicates are possible → consumers must be idempotent (eventId, [task 11](../04-kafka/02-reliability-dlt-idempotency.md)).
5. Different consumer groups each get **all** messages — that's fan-out (order-service reacts, analytics counts), not competition.

---

## 04.2 · Kafka Advanced
*Full doc: [`../04-kafka/02-reliability-dlt-idempotency.md`](../04-kafka/02-reliability-dlt-idempotency.md)*

**Mental model:** Everything in advanced Kafka is a response to two forces:
**membership changes** (rebalancing redistributes partitions — a stop-the-world
event) and **at-least-once delivery** (duplicates and poison messages *will*
happen). The mature consumer is armored on three sides: retries + DLT so one
bad message can't block a partition, an eventId ledger so duplicates are no-ops,
and lag monitoring so falling behind is visible before customers notice.

```
poison pill without DLT:  [bad msg] FAIL→retry→FAIL→retry→... ← partition FROZEN
with @RetryableTopic:     main → retry-0(1s) → retry-1(2s) → retry-2(4s) → topic.dlt → ack, moves on
```

**Five rules you must never get wrong:**
1. Rebalance = stop-the-world for the group; uncommitted work gets reprocessed after. **Sticky assignor** minimizes partition movement.
2. DLT pattern: bounded retries with exponential backoff, then park in `.dlt` and **commit** — unblocking beats completeness; humans handle the parked ones.
3. Retry *transient* errors only — validation errors (`IllegalArgumentException`) go **straight to DLT**; retrying a permanently bad message is theater.
4. Idempotent consumer = `processed_events` table with **unique eventId**, checked-and-inserted in the **same @Transactional** as the business work.
5. **Lag** = log-end-offset − committed-offset, per partition. It's THE consumer health metric; alert on it.

---

## 05 · Testing
*Full doc: [`../05-testing.md`](../05-testing.md)*

**Mental model:** Tests form a **pyramid of context sizes**. The less Spring you
load, the faster and more precise the test — so load exactly the slice under
test and fake the rest. Pure Mockito (no context, ms) → `@WebMvcTest` /
`@DataJpaTest` (one slice) → `@SpringBootTest` + TestContainers (everything,
real Postgres/Kafka in Docker). Speed is a feature: fast tests run every build;
slow ones get skipped and rot.

```
            @SpringBootTest + containers   ← few, slow, REAL (mvn verify / Failsafe, *IT)
        @WebMvcTest      @DataJpaTest      ← slice: web only / JPA only
   @ExtendWith(MockitoExtension)           ← many, milliseconds, no Spring (mvn test / Surefire, *Test)
```

**Five rules you must never get wrong:**
1. `@Mock` + `@InjectMocks` = **no Spring at all**; `@MockBean` = a mock **inside** a Spring context (slices). Different worlds, don't mix them up.
2. `@WebMvcTest` loads controllers + advice + validation only — service is `@MockBean`; it tests HTTP semantics (status, JSON shape, validation), not business logic.
3. `@DataJpaTest` + TestContainers + `Replace.NONE` = real PostgreSQL; `entityManager.flush()` + `clear()` before asserting, or you're testing the cache, not the query.
4. Naming split is the build architecture: `*Test` → Surefire (`mvn test`, no infra), `*IT` → Failsafe (`mvn verify`, needs Docker).
5. Coverage gates lie unless scoped: JaCoCo 0.80 **per tested class** (with includes), not bundle-wide where dead config classes dilute the number.

---

## 06 · Security & JWT
*Full doc: [`../06-security.md`](../06-security.md)*

**Mental model:** Spring Security is a **chain of servlet filters standing in
front of your controllers**; authentication is "who are you" resolved into a
`SecurityContext`, authorization is "may you" checked against it. JWT makes the
whole thing **stateless**: the server's only memory is its signing key —
identity travels in the token, verified by signature on every request, no
session store anywhere.

```
request ──▶ [JwtAuthFilter: Bearer token → verify signature → load user
             → SecurityContextHolder.set(auth)] ──▶ [authorize rules] ──▶ controller
login:   POST /auth/login {email,pw} → AuthenticationManager verifies (BCrypt)
         → jwtService.generateToken() → client sends "Authorization: Bearer ..." forever after
```

**Five rules you must never get wrong:**
1. JWT = `header.payload.signature` — the signature proves *integrity*, not secrecy: **the payload is readable base64**; never put secrets in claims.
2. Stateless API = `SessionCreationPolicy.STATELESS` + CSRF disabled — CSRF only matters when the browser auto-attaches credentials (cookies).
3. Passwords: **BCrypt** (slow on purpose, salted per password); never MD5/plain SHA.
4. Secrets come from the environment (`${JWT_SECRET}`), never from application.yml in git.
5. JWT can't be un-issued — revocation needs short TTL + refresh tokens (or a Redis blacklist, sacrificing statelessness).

---

## 07 · Observability
*Full doc: [`../07-observability.md`](../07-observability.md)*

**Mental model:** Observability answers three different questions with three
different signals: **metrics** = "how much/how fast, in aggregate" (cheap,
always on, alertable), **traces** = "where did THIS request spend its time
across services" (sampled), **logs** = "what exactly happened" (task 7). A
mature service exposes them all through one stack: Actuator endpoints →
Micrometer facade → Prometheus scrape → Grafana dashboards, plus trace IDs
stitching services together.

```
/actuator/health   ← K8s probes (liveness ≠ readiness!)
/actuator/prometheus ← scraped every 15s ──▶ Prometheus ──▶ Grafana + alerts
request ──[traceId propagated: HTTP headers, Kafka headers]──▶ Zipkin waterfall
```

**Five rules you must never get wrong:**
1. **Micrometer is SLF4J for metrics** — code against the facade, swap backends (Prometheus/Datadog) via one dependency.
2. Meter types by question: **Counter** = events ever (monotonic), **Gauge** = current level (up/down), **Timer** = latency *distribution* with percentiles.
3. **Liveness ≠ readiness**: liveness "restart me if false" (deadlock), readiness "no traffic yet" (warming up, dependency down). Wiring a dependency into liveness turns a DB blip into a restart storm.
4. Trace sampling in prod ≈ 1–10%, never 100% — and percentiles come from histograms (`percentiles-histogram: true`), you can't average p99s.
5. Alert on symptoms users feel: error rate, p99 latency, connection-pool pending, consumer lag — not on CPU.

---

## 08 · Caching
*Full doc: [`../08-caching.md`](../08-caching.md)*

**Mental model:** A cache is a **bet that the past predicts the near future** —
and the whole discipline is managing when that bet goes stale. Spring's cache
abstraction is (yet another) **proxy** around your methods; the hard parts are
never the annotations, they're the *policies*: what key, what TTL, who evicts,
and what happens when many callers miss at once.

```
@Cacheable   hit → skip method entirely | miss → run + store
@CachePut    ALWAYS run, then store     (writes: freshness over savings)
@CacheEvict  remove key (or allEntries) (writes: invalidate readers)

L1 Caffeine (in-JVM, sub-ms, per-pod) → L2 Redis (shared, ~1ms, survives restarts) → DB
```

**Five rules you must never get wrong:**
1. `@Cacheable` skips the method on a hit; `@CachePut` never skips — mixing them up either serves stale data forever or caches nothing.
2. **Every write evicts or puts.** A cached read path with a non-evicting write path = permanent staleness; TTL is the safety net, not the strategy.
3. TTL by volatility: seconds for stock/prices, minutes for listings, hours for reference data.
4. Know the three failure modes: **stampede** (many misses on one hot key), **penetration** (misses on keys that don't exist), **avalanche** (synchronized mass expiry) — each has a named fix.
5. It's a proxy: self-invocation (`this.getOrderById()`) **bypasses the cache** — same trap as @Transactional/@Async.

---

## 09 · Resilience
*Full doc: [`../09-resilience.md`](../09-resilience.md)*

**Mental model:** In a distributed system, **failure is contagious** — a slow
dependency eats your threads, your callers' threads, and so on up the chain
(cascading failure). Resilience patterns are **circuit breakers in the
electrical sense**: they sacrifice completeness ("some calls fail fast /
degrade") to protect the whole house. Each pattern guards one contagion vector.

```
CLOSED ──(failure rate > 50% over window)──▶ OPEN ──(wait 30s)──▶ HALF_OPEN
  ▲                                            │ fail fast,           │ 3 probe calls
  └────────── probes succeed ◀─────────────────┴──── probes fail ─────┘
```

**Five rules you must never get wrong:**
1. Pattern → threat: **Timeout** = one slow call; **Retry** = transient blip; **Circuit breaker** = sustained outage; **Bulkhead** = one dependency hogging all threads; **Rate limiter** = protecting yourself from callers.
2. OPEN means **fail immediately without calling** — the fast failure IS the feature: your threads stay free, the sick service gets air to recover.
3. **Retry needs backoff + jitter and only on transient errors** — retrying a 400, or retrying without jitter into a recovering service (retry storm), makes everything worse.
4. Order matters when stacking: timeout inside retry inside breaker — and retries multiply load (3 attempts × N callers), so the breaker must see the failures.
5. A fallback must be **honestly degraded** (cached/default/queued/feature-off) — never fabricated data that looks real.

---

## 10 · Microservices
*Full doc: [`../10-microservices.md`](../10-microservices.md)*

**Mental model:** Splitting the monolith trades in-process certainties for
network problems, and every Spring Cloud component is the replacement for
something a monolith gave you free: method call → **Feign + discovery + LB**;
one process entry → **Gateway**; one application.yml → **Config Server**; and —
the big one — `@Transactional` across modules → **Saga + Outbox**, because
there are no distributed transactions worth having.

```
client ──▶ GATEWAY (auth, rate limit, routing) ──lb://──▶ order-service ──Feign──▶ inventory-service
                     │                            ▲
                     └──────── EUREKA (who's alive & where) ◀──── every service registers/heartbeats
CONFIG SERVER (git-backed yml) ──▶ all services at startup (+ @RefreshScope live)
```

**Five rules you must never get wrong:**
1. `lb://service-name` = Eureka lookup + client-side load balancing — no hardcoded hosts anywhere.
2. The gateway is the **smart edge** (L7): authN once, rate limiting, breakers, header injection — so internal services trust `X-User-ID` from it instead of re-parsing JWTs... which means internal services must not be reachable from outside.
3. **No 2PC.** Cross-service consistency = Saga: a chain of local transactions, each with a **compensating action** (release stock, refund) — eventual consistency by design.
4. **Outbox** closes the dual-write gap: event row saved in the SAME local transaction as the data; a relay publishes it later → at-least-once, ordered per aggregate.
5. Every network hop needs the [phase 7](../09-resilience.md) armor (timeout/retry/breaker/fallback) and correlation-ID propagation ([task 7](../01-foundations/07-logging-mdc-correlation-ids.md)) — Feign interceptors do both.

---

## 11 · AOP
*Full doc: [`../11-aop-proxies.md`](../11-aop-proxies.md)*

**Mental model:** AOP is **the machinery you've been using all along, finally
with the cover off**. @Transactional, @Async, @Cacheable, @PreAuthorize — every
one is an aspect: a proxy wrapping your bean, running advice around matching
methods. Phase 9 just hands you the wrench: write your own annotation + advice
and any cross-cutting concern (timing, auditing) collapses from N copy-pastes
into one class.

```
caller ──▶ [PROXY: @Around advice
              before-part → pjp.proceed() → after-part ] ──▶ real method
Aspect  = the class holding the advice
Pointcut = WHICH methods (execution(...), @annotation(...))
Advice   = WHAT runs (Before / After / AfterReturning / AfterThrowing / Around)
```

**Five rules you must never get wrong:**
1. `@Around` must call `pjp.proceed()` and **return its result** — forget either and every matched method silently does nothing / returns null.
2. Advice observes, never swallows: catch → log → **rethrow**.
3. Proxy limits: **public methods, external calls only** — private methods and `this.method()` are invisible to Spring AOP (that's WHY @Transactional has those same rules).
4. Custom-annotation recipe: `@Target(METHOD) @Retention(RUNTIME)` annotation → `@Around("@annotation(param)")` advice with the annotation as a parameter → config lives in annotation attributes.
5. Layer-wide interception → `execution(* com.x.service..*(..))`; opt-in per method → `@annotation(...)`. Prefer opt-in for anything with a cost.

---

## 12 · Advanced Spring
*Full doc: [`../12-spring-advanced.md`](../12-spring-advanced.md)*

**Mental model:** This phase is about **which beans exist, how long they live,
and with what config** — the container's decision-making. Three axes:
**scope** (one instance or many, per what), **conditions/profiles** (does the
bean exist in THIS environment), **externalized config** (same jar, different
values everywhere). Plus API versioning: the same discipline applied to your
public contract.

```
scope:      singleton (default, stateless!) · prototype · request · session
existence:  @Profile("prod") · @ConditionalOnProperty/Class/Bean/MissingBean
config:     application.yml  ⊕  application-{profile}.yml (override)  ⊕  ${ENV_VARS}
lifecycle:  new() → inject → @PostConstruct → serve → @PreDestroy
```

**Five rules you must never get wrong:**
1. Singletons are shared by every request thread → they must be **stateless** (mutable fields on a @Service = race condition — [02-concurrency/03](../02-concurrency/03-atomicity-races-cas.md)).
2. Injecting shorter-lived into longer-lived needs indirection: request-scoped into singleton = **scoped proxy**; prototype into singleton = `ObjectProvider`/`@Lookup` (plain @Autowired freezes ONE instance forever).
3. `@ConditionalOnProperty` + `@ConditionalOnMissingBean` = the feature-flag/fallback duo — and the backbone of Boot's ENTIRE auto-configuration.
4. Profile files **override** base: application.yml holds the invariants, application-{dev,prod,test}.yml the differences; prod values come from `${ENV_VARS}`.
5. Group config into validated `@ConfigurationProperties` POJOs; `@Value` only for one-offs. Version APIs via URI path; **additive changes don't need a new version — removals/type changes do.**

---

## 13 · DB Advanced
*Full doc: [`../13-database-advanced.md`](../13-database-advanced.md)*

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

## 14 · API Docs & Standards
*Full doc: [`../14-api-documentation.md`](../14-api-documentation.md)*

**Mental model:** Your API contract already exists — SpringDoc just **reads it
off your code** (mappings, @Valid constraints, DTO types) and renders
OpenAPI JSON + Swagger UI. Annotations are for the parts code can't express:
descriptions, examples, which errors an endpoint returns. Task 53 is the same
discipline aimed at list endpoints: one standardized paged envelope, and
**every user-supplied sort/filter validated against a whitelist**.

```
code (controllers + DTOs + Bean Validation) ──SpringDoc──▶ /api-docs (OpenAPI 3 JSON)
                                                        └─▶ /swagger-ui.html (interactive)
list endpoint contract: content[] + page/size/totalElements/totalPages + next/prev links
```

**Five rules you must never get wrong:**
1. SpringDoc (not Springfox — dead, no Boot 3) generates from code; `@Operation`/`@Schema` add the human layer: descriptions, **examples**, error responses.
2. Document your error contract: every endpoint's @ApiResponse list includes the ErrorResponse shape for 400/404/422 — the [task 2](../01-foundations/02-exceptions-validation.md) contract, published.
3. **Swagger in prod**: disabled, role-protected, or internal-only — an open Swagger UI is a machine-readable attack map.
4. **Whitelist sort fields.** `sortBy` from the user must match an allowed list — via Pageable it's a runtime error, via native SQL it's injection.
5. Cap page size (`@Max(100)`) — `?size=1000000` is a self-service DoS.

---

## 15 · DevOps
*Full doc: [`../15-devops-docker-k8s.md`](../15-devops-docker-k8s.md)*

**Mental model:** The whole phase is one pipeline: **code → immutable image →
declared desired state → automated promotion**. Docker freezes the app + JRE
into an image; Compose declares the local universe (app + Postgres + Kafka +
Redis); Kubernetes runs the same image N times and *reconciles reality toward
your YAML* (you never "start pods" — you declare 3 replicas and K8s makes it
so); CI/CD is the conveyor: test → build/push image → `kubectl set image` →
watch the rollout.

```
Dockerfile:  [builder: maven+JDK → jar]  →  [runtime: JRE-alpine + non-root user + jar]  ~180MB
K8s:         Deployment(3 replicas, RollingUpdate maxUnavailable:0) ← probes gate traffic
             Service(ClusterIP) → pods | ConfigMap(config) + Secret(credentials) | HPA(2–10 @70% CPU)
CI:          push → test job → build+push :git-sha → kubectl set image → rollout status
```

**Five rules you must never get wrong:**
1. Multi-stage: build with JDK+Maven, ship only the jar on a JRE base — 3× smaller, no compilers in prod, plus the **pom-first COPY** so dependency layers cache.
2. Containers run **non-root** (`USER appuser`) with memory-aware JVM flags.
3. Zero-downtime = RollingUpdate `maxUnavailable: 0` + **readiness probes** + graceful shutdown, with `terminationGracePeriodSeconds ≥` Spring's shutdown timeout.
4. K8s Secrets are **base64, not encryption** — prod secrets come from Vault/External Secrets; config in ConfigMaps, credentials in Secrets, never in images.
5. Images are tagged with the **git SHA** (immutable, traceable, rollback-able) — `latest` is not a deployment strategy.

---

## 16 · Production Hardening
*Full doc: [`../16-production-hardening.md`](../16-production-hardening.md)*

**Mental model:** Hardening = making change boring. Three separations do it:
**deploy ≠ release** (feature flags: code ships dark, a toggle releases it),
**new version ≠ new traffic** (blue-green switches atomically, canary ramps a
percentage — both make rollback a routing change, not a redeploy), and
**process exit ≠ dropped work** (graceful shutdown drains everything before
dying). Underneath all three: **expand-contract migrations**, because during
any rollout old and new code run against ONE schema simultaneously.

```
flags:      if (flags.isEnabled("new-checkout")) newPath else oldPath   ← rollback = toggle
blue-green: Service selector slot:blue → slot:green (atomic, instant back)
canary:     Ingress canary-weight: 10 → 25 → 50 → 100 (watch metrics between steps)
shutdown:   SIGTERM → stop intake → drain HTTP → commit Kafka offsets → close pool → exit
```

**Five rules you must never get wrong:**
1. Feature flags decouple deploy from release: dark launches, per-user betas, instant kill switches — and they enable trunk-based development.
2. Blue-green = two full environments, one selector flip — instant rollback, double cost. Canary = weighted ramp — small blast radius, needs traffic-splitting infra + metrics discipline.
3. During ANY rollout, **old code and new schema coexist** → migrations must be backward compatible: add nullable/defaulted, never drop/rename/retype in the same release.
4. Rename-a-column = the 3-deploy dance: add new column → write both/backfill → read new → (later) drop old.
5. `terminationGracePeriodSeconds` (K8s) > `timeout-per-shutdown-phase` (Spring) — SIGKILL before the drain finishes = dropped requests, uncommitted offsets, leaked connections.

---
