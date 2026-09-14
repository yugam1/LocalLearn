# 🃏 Master Flashcard Deck

> All decks, in curriculum order. Each line inside a block is one card:
> `front<TAB>back`. **Anki import:** copy blocks into a .txt file, then
> File → Import, fields separated by Tab. Or drill straight from this file:
> cover the right side, answer aloud.

## 01.1 · REST API, DI & Layers

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

## 01.2 · Exceptions & Validation

```
@Valid missing on @RequestBody	Constraints silently ignored — validation never runs
@RestControllerAdvice = ?	@ControllerAdvice + @ResponseBody, catches from ALL controllers
Validation failure exception	MethodArgumentNotValidException → 400 + all field errors
Strictest not-X for strings	@NotBlank (rejects null, "", " ")
Cascade validation into a nested object/list	@Valid on the FIELD (not automatic)
422 means	Well-formed request, business rule rejects (stock, state)
Handler selection with multiple matches	Most specific exception type wins
Custom validator null convention	return true — @NotNull owns null
Why unchecked business exceptions	No throws litter; bubbles to advice; also = default rollback trigger
Stack trace in response?	Never — log internally, generic message + correlation id out
```

## 01.3 · JPA Entities & HikariCP

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

## 01.4 · Specifications & Projections

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

## 01.5 · Transactions

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

## 01.6 · N+1 & Performance

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

## 01.7 · Logging, MDC & Correlation IDs

```
MDC backing + async consequence	ThreadLocal map — new/pooled threads start EMPTY
MDC → @Async fix	TaskDecorator: getCopyOfContextMap → setContextMap → clear (finally)
MDC hygiene rule	MDC.clear() in finally — else context bleeds across pooled threads
Correlation ID through Kafka	Producer: event.setCorrelationId(MDC.get) · Consumer: MDC.put, then clear
Exception in a log call	Last argument, no placeholder: log.error("x={}", x, ex)
Parameterized vs concat	Concat builds the string even when level is OFF (and may fire toString/lazy loads)
Alerting keys on	ERROR only — WARN is reviewed, INFO is audit trail
AsyncAppender discardingThreshold: 0	Never drop events (may block); default drops <WARN under pressure
additivity="false"	Named logger's events don't also hit root appenders (no duplicates)
Never log	Passwords, tokens, full PANs — mask: j***n@, last4
```

## 02.1 · Threads & Interruption

```
interrupt() does exactly	Sets one boolean flag — nothing else for a running thread
InterruptedException side effect	The throw CLEARS the interrupt flag
Swallowed InterruptedException	Cancellation request destroyed forever — restore or rethrow
CPU-bound loop cancellation	Must poll isInterrupted() — blocking calls aren't there to throw
Thread.interrupted() trap	Static: reads AND CLEARS the flag
RUNNABLE state lie	Includes threads blocked on socket I/O — read stacks, not state words
BLOCKED means only	Waiting for a synchronized monitor (ReentrantLock waits show WAITING)
Daemon thread at JVM exit	Killed in place — no finally, no flush; set before start()
Worker exception destination	UncaughtExceptionHandler; in pools → captured in Future, lost without get()
Why no Thread.stop()	Async exception could release a monitor mid-update → corrupt shared state
Platform thread cost	~1ms create, ~1MB stack, 1:1 OS thread, ceiling ≈ few thousand
```

## 02.2 · Java Memory Model

```
Stale stop-flag culprit	The JIT hoists the read out of the loop (not CPU caches) — -Xint proves it
volatile's three guarantees	Access atomicity · visibility (no hoisting) · ordering (release/acquire edge)
volatile does NOT give	Atomicity of compound actions — volatile n++ still loses updates
Happens-before contract	A hb B → A's effects visible to B; no edge → guaranteed NOTHING
Free hb edges	Program order · monitor unlock→lock · volatile write→read · start · join · final freeze · transitivity
A lock is also	A memory barrier — readers need the edge too, not just writers
new Config(42) hidden steps	allocate → construct → publish; construct/publish may reorder
Broken DCL fix	volatile instance field (or holder-class idiom)
final field guarantee	Published after constructor → correct values, zero synchronization (JLS 17.5)
x86 false safety	TSO hides store-store reordering — ARM will expose it; can't test to correctness
Shared plain HashMap	Can splice a bucket cycle → spins forever at 100% CPU, ignores interrupt
```

## 02.3 · Races, Atomicity & CAS

```
count++ is really	READ, MODIFY, WRITE — three bytecodes, racy gap between them
volatile counter, 8×100k	Lost ~72% — volatile changes timing, not semantics
Check-then-act bug location	The GAP: the checked condition is stale by the act
Half-fix (atomic pieces only)	Rarer bug = worse bug — survives tests, ships to prod
Three real fixes	CAS retry loop (1 variable) · lock (multi-field invariant) · DB UPDATE...WHERE / @Version (multi-JVM)
CAS in one sentence	"If still equals expected, set to next" — one CPU instruction, no parking
Lock-free vs wait-free	System always progresses vs THIS thread always progresses (CAS loop is only lock-free)
ABA problem	CAS compares values not history; A→B→A slips through → AtomicStampedReference / @Version
AtomicLong vs LongAdder @16 threads	222ms vs 23ms — adder shards per-thread cells, no shared cache line
LongAdder trade-off	sum() is O(cells) and NOT an atomic snapshot; no getAndIncrement
When lock beats atomic	Invariant spans >1 variable, or contention makes retry storms cost more than parking
```

## 02.4 · Locks & Deadlock

```
synchronized's killer feature	JVM releases the monitor even on exception — cannot leak a lock
ReentrantLock's four reasons	tryLock(timeout) · lockInterruptibly · fairness · multiple Conditions
Fair lock cost (measured)	~92× slower — every handoff is a park/unpark syscall
Coffman's four	Mutual exclusion · hold-and-wait · no preemption · circular wait (break ANY one)
First-choice deadlock fix	Lock ordering (ascending id) — breaks circular wait, nothing to tune
tryLock+backoff without jitter	Livelock: RUNNABLE, busy, zero progress, clean-looking dump
Deadlock diagnosis	jcmd <pid> Thread.print → "Found one Java-level deadlock"; ThreadMXBean.findDeadlockedThreads()
Detection blind spot	Semaphore/latch deadlocks invisible — plain hang, no report
RWLock real rule	Reads dominate AND read holds lock >~1µs; 0ns reads → RWLock loses even at 100%
Read→write upgrade	Instant deadlock — release read, take write, RE-CHECK condition
while not if around await()	Spurious wakeups · signalAll wakes wrong threads · signal-steal in the gap
Cache stampede fix	computeIfAbsent (per-bin lock); slow loader → cache a CompletableFuture
```

## 02.5 · Hand-off, Queues & Backpressure

```
Producer outruns consumer — options	Block (backpressure) · Drop (shed) · Grow (OOM later). No fourth.
Unbounded queue, measured	1,000,000 items / ~140 MB in 419ms vs bounded flat at 1,024
Capacity is	WHERE YOUR SYSTEM FAILS, chosen on purpose — not a tuning knob
The decision rule	Bounded unless you can PROVE the producer is rate-limited
poll() vs take() vs poll(timeout)	null now (loop = busy-wait) · parks (deaf to flags) · parks but wakes — use this
volatile flag + take()	HANGS. Parked thread executes nothing → re-checks nothing. Visible ≠ awake
D4 hang vs D15 hang	Read optimised away  vs  reader is parked. Same symptom, opposite cause
interrupt() shutdown	Prompt AND lossy — measured 1,256 of 10,000 abandoned
Poison pill	In-band sentinel, FIFO ⇒ arrives after every real item ⇒ "saw pill" proves "drained"
Pill compared with	== never equals() — must be THAT instance; N consumers need N pills
Pill : interrupt ::	shutdown() : shutdownNow()  — drain vs abandon
SynchronousQueue	Capacity ZERO, rendezvous. offer() fails unless a taker is parked → newCachedThreadPool
ArrayBQ vs LinkedBQ	1 lock + prealloc array  vs  2 locks (put/take) + node per item
Measured 1P/1C vs 4P/4C	ArrayBQ 289/134 — FASTER with more threads (park/unpark dominates, held 7/7 runs)
DelayQueue	Items invisible until their delay expires — how ScheduledThreadPoolExecutor works
PriorityBlockingQueue	Unbounded and NOT FIFO — both facts bite
```

## 02.8 · ThreadPoolExecutor Internals

```
Submission rule	core → QUEUE → max → reject. Queue is tried BEFORE growth
Why queue before growth	A queue slot is a pointer; a thread is ~1MB + a scheduler entity
maxPoolSize is dead when	The queue is large — it never fills, so step 3 never runs
Measured: core=2 max=10 queue=100	60 tasks → poolSize 2, queued 58. 8 permitted threads never created
Same pool, queue=4	poolSize 10, accepted 14, REJECTED 46 — one number changed
Pool capacity	max + queueCapacity. Beyond that: the rejection handler
newFixedThreadPool danger	Unbounded LinkedBlockingQueue — 1,000,000 tasks queued in 104ms, no refusal
newCachedThreadPool danger	SynchronousQueue (cap 0) ⇒ always grows. 1,000 tasks → 1,000 threads
Rejection ≡ topic 5	CallerRuns=block · Abort/Discard*=drop · unbounded queue=grow
Measured rejection	Discard lost 1,990/2,000 in <1ms; CallerRuns lost 0 in 668ms (6/6 runs)
CallerRuns mechanism	Submitter runs the task ⇒ not in its submit loop ⇒ arrival rate → 0
DiscardOldest drops	The queue HEAD — the requests that already waited longest
Sizing, one question	What fraction of the task actually HOLDS a core?
CPU-bound measured	5.3x at 6 threads (6 physical cores), plateau ~10.7x. NOT slower — flat
IO-bound measured	13 threads 12.5x vs 48 threads ~42x — 'cores+1' on IO work costs 3.4x
Past ~96 threads	Did NOT replicate: 54x–77x at 240 across runs. Formula ⇒ magnitude only
shutdown() vs shutdownNow()	Drain (ran 50/50) vs abandon (ran 6/50, RETURNED 44 unstarted)
shutdownNow's return value	The abandoned tasks — data loss made countable. Do not discard it
awaitTermination returns	A boolean, FALSE on timeout. Almost nobody reads it
shutdownNow vs a CPU loop	Cannot stop it. 1.5s task ran all 1,501ms — interruption is a REQUEST
submit() exception	Caught by FutureTask, stored, NOT rethrown → silence until get()
execute() exception	UncaughtExceptionHandler fires, worker DIES, pool replaces it
```

## 02.9 · ForkJoin, Parallel Streams & Virtual Threads

```
The one question this topic asks	What KIND of work is this? CPU-splittable / CPU-independent / IO
ForkJoin ordering rule	right.fork(); left.compute(); right.join()
fork(); join(); per half	NO parallelism — measured 0.5-0.7x, SLOWER than a plain loop (6/6)
Why per-worker deques	Push/pop own at HEAD lock-free & cache-warm; steal OLDEST from TAIL
Why steal the oldest	It is the BIGGEST chunk — one steal buys lots of work, steals stay rare
Leaf threshold	Both ends bad, middle is a 4-orders-of-magnitude plateau. Roughly right is enough
.parallel() submits to	ForkJoinPool.commonPool() — JVM-wide, cores-1, shared, NOT bulkheadable
Common-pool starvation	Unrelated CPU stream 7.0-9.8x slower while 44 tasks blocked (6/6 runs)
Parallel stream criterion	N x cost-per-element, TOTAL. Element count alone predicts nothing
Boxing cost measured	Stream<Long> ~35x slower sequential, ~19x parallel, vs LongStream
Virtual thread =	A continuation + JVM scheduler; heap stack from a few hundred bytes
Mount / unmount	Blocking JDK call → frames to heap, carrier freed. Blocked ⇒ NO OS thread
Creation cost measured	~2.3us virtual vs ~120us platform — ~50x cheaper
Scale measured (10k x 100ms)	pool-of-12: 115/sec · pool-of-1000: 8,700/sec · virtual: 63,700/sec
Virtual threads are NOT	A pool. newVirtualThreadPerTaskExecutor is a FACTORY — never pool them
PINNING (Java 21)	synchronized holds the CARRIER's monitor ⇒ cannot unmount ⇒ blocks an OS thread
Pinning measured	110ms → 14,400-17,400ms. 105-156x. ZERO contention (per-task locks)
Pinning is bimodal	9/11 runs 105-156x; 2/11 ~9.5x when the scheduler grew the carrier pool
Pinning fix	ReentrantLock (0.8-1.0x) — better: hold NO lock across a blocking call
Find pinning with	-Djdk.tracePinnedThreads=full
Virtual threads for CPU work	Gain nothing — nothing blocks, so nothing unmounts
StructuredTaskScope guarantee	Control leaves the block ⇒ every fork inside has ALREADY finished
Unstructured cost measured	Failure at 5ms reported at 2,033ms; doomed sibling ran to completion
Java 21 status	StructuredTaskScope + ScopedValue are PREVIEW — need --enable-preview
ScopedValue vs ThreadLocal	Immutable + block-scoped vs mutable map per thread. All differences follow
```

## 02.10 · Diagnostics & The Incident

```
Thread dump, the two commands	jcmd <pid> Thread.print -l  ·  jstack <pid>. Take TWO, 30s apart
The frame you want in a dump	NEVER the top one — skip Unsafe.park/LockSupport, find the first frame in YOUR package
Dump header hidden gem	cpu= and elapsed= per thread. cpu≈elapsed = your spinner, no profiler needed
findDeadlockedThreads() null means	"not a lock-OWNERSHIP cycle" — NOT "no hang". Narrows, never clears
The two detectors differ by	findMonitorDeadlocked = monitors only; findDeadlocked = monitors + AQS. Divergence names the lock type
Measured detector divergence	sync cycle 2/2 · +ReentrantLock cycle 4/2 · +Semaphore cycle 4/2 (unchanged — invisible)
Invisible to every deadlock tool	Semaphore permits · CountDownLatch · Future.get · empty queue — no owner, no cycle
RUNNABLE lies twice	Includes native socket reads (idle) AND a thread pinning a core. Read frames + cpu=
BLOCKED means only	Intrinsic synchronized monitor. ReentrantLock deadlock says WAITING
Angle brackets name the mechanism	FutureTask=pool on itself · CountDownLatch$Sync=uncounted latch · ConditionObject=queue · Semaphore$NonfairSync=permits
Pool blocked on its own pool	Every worker WAITING in FutureTask.awaitDone; active==max, queued>0, both FROZEN over 2 samples
Why pool exhaustion is silent	Unbounded queue ⇒ nothing rejected, nothing thrown; no lock owner ⇒ no detector. It just stops
The pool rule	Never block a pool thread on work only that same pool can do. Needs elsewhere? Give it its OWN pool
ThreadLocal on a pooled thread	Thread outlives the task ⇒ next task inherits the last tenant. Measured 200/200 wrong
ThreadLocal fix needs BOTH	set() unconditionally (absent overwrites) AND remove() in a finally. Correctness, not hygiene
Wrong data vs hang	No dump/detector/profiler sees wrong data. Only comparing input to output. Runs for weeks unnoticed
100% CPU + flat progress counter	Working hard at nothing. EITHER number alone is ambiguous — you need both
Busy-wait vs livelock	One thread in a poll() loop  vs  SEVERAL in a tryLock retry with no jitter (dump looks healthy)
Thread leak, the three counters	live=is there a leak · totalStarted=still happening? · peak=compare after the fix
JFR ExecutionSample is a CPU sampler	Nails a spinner (1652/1652 samples on 2 reapers). Near-USELESS for a hang — nothing is running
Empty JFR profile during an outage	A positive result: nothing is executing ⇒ threads are stopped ⇒ take a dump
Name your threads	A ThreadFactory is 4 lines. "pool-3-thread-7" costs you 20 minutes you do not have
```

## 03.1 · Thread Pools & @Async

```
Pool growth order	core → QUEUE → max → reject (queue before growth!)
maxPoolSize dead when	Queue is large/unbounded — it fills first, pool never grows
CPU-bound / IO-bound sizing	cores+1 / cores × (1 + wait/cpu)
Queue size formula	peak_RPS × avg_duration_s × safety_factor
Production rejection policy	CallerRunsPolicy — submitter runs task = backpressure, no loss
newFixedThreadPool danger	Unbounded queue → silent backlog → OOM
@Async self-invocation	Runs synchronously — proxy bypassed, no warning
@Async void + exception	Swallowed — AsyncUncaughtExceptionHandler or return CompletableFuture
allOf wall time	= slowest future (parallel); chained thenApply = sum
supplyAsync without executor	ForkJoinPool.commonPool — shared, unmanaged, no MDC
```

## 03.2 · @Scheduled & ShedLock

```
fixedRate measures from	START of previous run (can stack back-to-back)
fixedDelay measures from	END of previous run (overlap impossible)
Default @Scheduled threads	ONE — all tasks serialize; always configure ThreadPoolTaskScheduler
Spring cron field count	6 — seconds first: sec min hour dom month dow
Cron daily 2 AM	0 0 2 * * ?
? in cron	"No specific value" — required for the unused one of dom/dow
N pods + @Scheduled	N executions — ShedLock for exactly-one
lockAtMostFor	Lease TTL — crash insurance; must exceed worst-case runtime
lockAtLeastFor	Minimum hold — clock-skew / fast-finish insurance
Scheduled task exceptions	Catch ALL in body; log+metric; never rethrow
Dynamic scheduling	taskScheduler.schedule(runnable, new CronTrigger(expr)) → ScheduledFuture
```

## 04.1 · Kafka Basics

```
Kafka in one phrase	Durable replayable log — consumers move bookmarks, messages stay
Ordering scope	One partition; key routes to partition → key = ordering decision
Partition:consumer within a group	1:max-1 — extra consumers idle; parallelism ceiling = partitions
Different consumer groups	Each gets ALL messages, own offsets (fan-out, not competition)
Critical-event producer settings	acks=all + enable-idempotence=true (+ retries)
Critical-event consumer settings	enable-auto-commit=false, manual ack AFTER processing
Auto-commit's two failure modes	Loss (commit before process + crash) and duplicates (reverse)
Commit-after-process delivery	At-least-once → handler must be idempotent (eventId)
auto-offset-reset	earliest = process history; latest = only new (per-group, first connect)
ErrorHandlingDeserializer	Poison message fails handleably instead of crash-looping the listener
Save-then-publish gap	Dual-write problem → outbox pattern
```

## 04.2 · Kafka Advanced

```
Rebalance in one line	Membership change → group-wide freeze → partitions redealt → resume (dupes possible)
Production assignor	Sticky/CooperativeSticky — minimal partition movement
DLT flow	Bounded backoff retries (separate retry topics) → .dlt → COMMIT → partition unblocked
Straight-to-DLT exceptions	Non-retryable (validation) — listed in exclude; retrying can't help
@DltHandler duties	Persist + alert + metric — unwatched DLT = polite data loss
Idempotency ledger	UNIQUE eventId row inserted in SAME transaction as the work
Duplicate arrives	Skip work, STILL ack (advance offset)
Ledger real race guard	The unique index, not the exists() check (TOCTOU)
Lag formula + alerts	log-end − committed, per partition; alert on size AND age
Batch failure semantics	Whole batch redelivered → per-event idempotency required
Replay command	--reset-offsets --to-earliest|--to-offset|--to-datetime --execute (group stopped)
```

## 05 · Testing

```
Test pyramid in Spring terms	Mockito (ms) → @WebMvcTest/@DataJpaTest (slice) → @SpringBootTest+containers (real)
@InjectMocks does	Constructs the real class, injects @Mock fields — no Spring
@MockBean does	Registers a Mockito mock as a bean in the (slice) context
@WebMvcTest proves	Routing, status, JSON, validation, advice — never persistence
@DataJpaTest honest-query ritual	persistAndFlush → clear → repository call → assert
Replace.NONE means	Keep MY datasource (the container), don't auto-swap H2
TestContainers wiring	@Container static + @DynamicPropertySource(url, user, pass)
*Test vs *IT	Surefire@test (no infra) vs Failsafe@verify (Docker)
Spy vs Mock	Real object selectively stubbed vs all-fake
JaCoCo honest gate	Per-class 0.80 with includes — not bundle-wide averages
assertThatThrownBy chain	.isInstanceOf(X.class).hasMessageContaining("…")
```

## 06 · Security & JWT

```
JWT anatomy	header.payload.signature — signed (integrity), NOT encrypted (readable)
JWT validation per request	Signature + expiry (+ optionally user lookup — costs statelessness)
Stateless pair of settings	SessionCreationPolicy.STATELESS + csrf disabled
CSRF applies when	Credentials auto-attach (cookies) — not Bearer headers
Browser token storage ranking	HttpOnly+SameSite cookie > in-memory > localStorage (XSS)
Revocation options	Short TTL+refresh · Redis blacklist · secret rotation · token-version claim
Password hash	BCrypt cost 10–12 — slow by design, per-password salt
hasRole('ADMIN') matches	Authority ROLE_ADMIN (prefix convention)
Ownership check annotation	@PostAuthorize("returnObject.x == authentication.name")
Method security machinery	Proxy — self-invocation SKIPS the check
CORS with credentials	Explicit origins only, never "*"; browser-only protection
```

## 07 · Observability

```
Three signals	Metrics = aggregate; Traces = per-request path; Logs = detail
Micrometer is	SLF4J for metrics — facade, backend = registry dependency
Counter / Gauge / Timer	Events-ever ↑ / current level ↕ / duration distribution
Fleet-wide p99 recipe	percentiles-histogram: true → histogram_quantile over merged buckets
Never tag metrics by	Unbounded values (userId/orderId) — cardinality explosion
Liveness failure ⇒ / readiness failure ⇒	Pod restarted / traffic drained (pod lives)
Dependency in liveness probe	Anti-pattern: outage → restart storm
Free Boot metrics	HTTP, JVM, GC, threads, HikariCP, Kafka lag
Trace vocabulary	Span (one op) share a traceId; propagated via headers (HTTP + Kafka)
Prod trace sampling	1–10% head-based; tail-based keeps errors/slow
Alert-worthy four	5xx rate, p99 latency, hikari pending, consumer lag
Runtime log level change	POST /actuator/loggers/<logger> {"configuredLevel":"DEBUG"}
```

## 08 · Caching

```
@Cacheable hit	Method NOT executed — value straight from cache
@CachePut	Always executes, then stores result (write path)
Write path rule	Every mutation evicts or puts — TTL is backstop, not strategy
Multi-level flow	Caffeine (ns, per-pod) → Redis (1ms, shared) → DB; promote on L2 hit
L1/L2 staleness rule	L1 TTL ≪ L2 TTL, or pub/sub invalidation broadcast
Stampede fix	Single-flight lock / early refresh (one rebuilds, rest wait)
Penetration fix	Cache negative results briefly, or Bloom filter
Avalanche fix	TTL jitter — never let a population expire together
Redis distributed lock	setIfAbsent + TTL (SET NX EX); unique token to unlock safely
Cache what	Read-heavy, staleness-tolerant, expensive to compute — DTOs, never entities
Proxy trap #4	this.cachedMethod() bypasses the cache
```

## 09 · Resilience

```
Threat → pattern map	Slow call→Timeout · blip→Retry · outage→Breaker · thread hogging→Bulkhead · abusive load→RateLimiter
OPEN state behavior	Fail instantly, no call made — frees caller threads + recovery air
HALF_OPEN	Few probe calls after waitDuration; success→CLOSED, fail→OPEN
Breaker config four	slidingWindowSize · failureRateThreshold · waitDurationInOpenState · permittedCallsInHalfOpen
Retry storm prevention	Exponential backoff + JITTER (+ breaker suppressing the fleet)
Never retry	Deterministic failures (400s) or non-idempotent writes without idempotency keys
Bulkhead	Max concurrent calls per dependency — ship-compartment isolation
Rate limiter semantics	limitForPeriod per refresh; timeout 0 → fail fast → 429
Fallback ladder	Labeled stale cache → safe default → queue async → feature off; never fake data
Cascading failure	Slow dependency → your threads exhaust → YOUR callers fail → repeat upward
Ops signal	Breaker OPEN >minutes = incident; Resilience4j → Micrometer automatically
```

## 10 · Microservices

```
lb://order-service	Eureka name resolution + client-side load balancing
Eureka design stance	AP — clients cache the registry; stale beats unavailable
Gateway vs LB	L7 smart edge (auth/rate/route/CB) vs L4 connection spreading
Gateway header identity precondition	Internal services only reachable VIA the gateway
Config Server + live reload	Git-backed yml; @RefreshScope + /actuator/refresh (Bus = fleet-wide)
Feign =	Declarative HTTP interface + Eureka + LB + fallback class
Correlation across services	Feign interceptor: MDC → X-Correlation-ID header
Why no 2PC	Cross-service locks + blocking coordinator = availability poison
Saga =	Local transactions chained by events + compensating actions (no rollback!)
Outbox atomic pair	Business row + event row in ONE local transaction; relay publishes after
Outbox delivery guarantee	At-least-once (publish-then-mark) → idempotent consumers required
Compensation examples	Release stock, refund payment, cancel order — forward transactions that undo
```

## 11 · AOP

```
Aspect / Pointcut / Advice / JoinPoint	Class of concern / which methods / what runs / one interception
@Around's two obligations	Call pjp.proceed() AND return its result (rethrow its exceptions)
Forgot proceed()	Every matched method silently never executes — returns null
Layer sweep pointcut	execution(* com.x.service..*(..)) — .. = subpackages, (..) = any args
Opt-in pointcut	@annotation(com.x.MyAnnotation), bound as advice parameter
Spring AOP mechanism	Runtime proxies (JDK dynamic / CGLIB) — public + external calls only
Fix for private/self-invocation	AspectJ compile/load-time weaving (bytecode, not proxies)
@Transactional/@Cacheable/@Async are	Aspects — same proxy, same rules, one mental model
Custom annotation skeleton	@Target(METHOD) @Retention(RUNTIME) + attributes with defaults
Aspect ordering	@Order — lower = outer; position changes semantics (timing vs tx)
Audit aspect judgment calls	logArgs=false by default (PII); decide audit-failure policy explicitly
```

## 12 · Advanced Spring

```
Singleton contract	Shared by all threads → stateless (final collaborators only)
Prototype trap	@Autowired into singleton = ONE frozen instance; use ObjectProvider/@Lookup
Prototype lifecycle gap	Spring never calls @PreDestroy on prototypes
Request-scoped into singleton	proxyMode = ScopedProxyMode.TARGET_CLASS (per-request resolution)
Lifecycle order	constructor → injection → @PostConstruct → serve → @PreDestroy
Feature-flag bean duo	@ConditionalOnProperty (real) + @ConditionalOnMissingBean (no-op fallback)
Auto-configuration =	@ConditionalOnClass/Property/MissingBean stacks; your beans win
Profile layering	application.yml base ⊕ application-{profile}.yml overrides ⊕ ${ENV}
Config groups	@ConfigurationProperties(prefix) + @Validated POJO; @Value for one-offs
Versioning default	URI path /api/v1/ — visible, routable, cacheable
Needs a new version	Remove/rename/retype fields, new required inputs — NOT additive optionals
```

## 13 · DB Advanced

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

## 14 · API Docs & Standards

```
SpringDoc endpoints	/api-docs (OpenAPI JSON) + /swagger-ui.html
Springfox status	Dead — no Spring Boot 3; SpringDoc always
Free vs annotated	Structure (routes, schemas, required) free; intent (desc, examples, errors) annotated
Highest-value @Schema attr	example — pre-fills try-it-out, clients copy it
JWT in Swagger	SecurityScheme bearer/JWT + SecurityRequirement → Authorize button
Swagger prod postures	Disabled / role-protected / internal-only — never open
sortBy handling	Whitelist → 400; Pageable is injection-safe but native SQL is not
size param	@Max(100) — unbounded size = self-service DoS
PagedResponse fields	content, page, size, totalElements, totalPages, first/last, next/prev links
Why the envelope	Stable contract, uniform across endpoints, hides pagination-strategy changes
Document side effects	@Operation description carries "publishes OrderCreated event"
```

## 15 · DevOps

```
Multi-stage split	Builder (JDK+Maven → jar) / runtime (JRE-alpine + jar only)
Layer-cache trick	COPY pom.xml + dependency:go-offline BEFORE COPY src
Container security basics	Non-root USER, JRE-only base, no secrets in image
JVM in a container	-Xmx (or MaxRAMPercentage) below the K8s memory limit
Compose networking	Service names are hostnames on the shared bridge network
depends_on gotcha	condition: service_healthy gates on healthcheck, not process start
Zero-downtime trio	maxUnavailable:0 + readinessProbe + graceful shutdown (grace ≥ drain)
Probes recap	Readiness gates traffic; liveness triggers restart — never dependencies in liveness
K8s Secret reality	base64 ≠ encryption — Vault/External Secrets + RBAC in prod
Image tagging	Push+deploy the git SHA; :latest breaks rollback determinism
Pipeline gate	kubectl rollout status --timeout → failed rollout = red build
Requests vs limits	Scheduler reservation vs OOM/throttle ceiling
```

## 16 · Production Hardening

```
Deploy vs release	Deploy = code in prod (dark); release = flag on — separable events
Flag tiers	Properties (restart) → @RefreshScope (refresh POST) → DB+cache (runtime, per-user, audited)
Kill switch	Ops flag turning a misbehaving integration off in seconds — no pipeline
Blue-green switch	Service selector slot patch — atomic, instant back; 2× infra; state doesn't roll back
Canary loop	Weight 5→25→50→100 with metrics gate each step; rollback = weight 0
Canary prerequisites	Weighted routing (Ingress/Istio/ALB) + per-version metrics
Graceful shutdown order	Stop intake → drain HTTP → commit Kafka offsets → drain schedulers → close pool → exit
Timeout inequality	K8s terminationGracePeriod > Spring timeout-per-shutdown-phase (+buffer)
Migration invariant	Old + new code share one schema mid-rollout → every migration dual-safe
Never in one release	Drop / rename / retype / NOT NULL-no-default
Rename dance	Add → write-both+backfill → read-new → drop later (3+ deploys)
CONCURRENTLY	Index build without write lock; non-transactional; failed = INVALID index
```
