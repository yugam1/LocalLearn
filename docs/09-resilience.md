# Resilience — Resilience4j Patterns
**⬜ Not Started · plan: Phase 7, Tasks 31–34** — Circuit breaker, retry, rate limiter, bulkhead, timeouts, fallbacks

---

## ⚡ CORE CARD — 60 seconds

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

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> No resilience patterns. inventory-service starts taking 30s per call (not erroring — just slow). order-service has 200 Tomcat threads and gets 50 req/s. What does order-service look like after 2 minutes, and to ITS callers?</summary>

**Dead — without any of its own code failing.** Each request parks a Tomcat
thread for 30s; at 50 req/s you exhaust 200 threads in ~4 seconds; every
endpoint (including ones not touching inventory) queues or times out. To
callers, order-service IS the outage — failure propagated upward. This is the
cascading failure that timeouts (cap the wait) + bulkheads (cap the threads one
dependency may hold) + breakers (stop calling entirely) exist to stop.
</details>

<details>
<summary><b>P2.</b> inventory-service recovers after a 2-minute outage. 500 pods all retry with fixed 1s delay, no jitter, no breaker. What happens in the first seconds of recovery?</summary>

A synchronized **retry storm**: all pods' retries land in the same instants,
hammering the barely-alive service back down — repeat forever (the distributed
cousin of livelock-in-lockstep from 02-concurrency/04). Fixes: exponential backoff
+ **random jitter** (spread the load), and the circuit breaker's HALF_OPEN
state, which lets only a handful of probe calls through instead of the whole
fleet.
</details>

<details>
<summary><b>P3.</b> <code>@Retry(maxAttempts=3)</code> on a call that fails with HTTP 400 (validation). What do the retries accomplish? And with maxAttempts=3 + a breaker counting failures, how many failures does ONE user request register?</summary>

Nothing — a 400 is deterministic; it will fail identically 3 times, adding
latency and load. Retry must be scoped to transient exceptions
(timeouts, 503s, connection resets) via retryExceptions/ignoreExceptions.
And one request = up to 3 recorded failures — retries amplify both load and
the breaker's failure count; that interplay is why annotation order/config
must be deliberate.
</details>

---

## 📖 THE STORY

### 1. The circuit breaker state machine

Config per named instance:

```yaml
resilience4j.circuitbreaker.instances.inventoryService:
  slidingWindowSize: 10                    # judge over the last 10 calls
  failureRateThreshold: 50                 # >50% failed → OPEN
  waitDurationInOpenState: 30s             # recovery air
  permittedNumberOfCallsInHalfOpenState: 3 # probes
```

- **CLOSED**: normal; failures counted over a sliding window (count- or
  time-based; slow calls can count as failures too —
  `slowCallDurationThreshold`).
- **OPEN**: every call fails instantly with `CallNotPermittedException` →
  fallback. No thread waits, no load lands on the sick service.
- **HALF_OPEN**: after the wait, a few probes pass through; success → CLOSED,
  failure → OPEN again.

The insight interviewers probe: the breaker protects **both sides** — the
caller's thread pool AND the callee's recovery.

### 2. The full toolkit, one threat each

```yaml
retry.instances.inventoryService:      { maxAttempts: 3, waitDuration: 1s,
                                         exponentialBackoffMultiplier: 2.0 }
timelimiter.instances.inventoryService: { timeoutDuration: 3s }
ratelimiter.instances.orderCreation:   { limitForPeriod: 100, limitRefreshPeriod: 1s }
bulkhead.instances.inventoryService:   { maxConcurrentCalls: 10, maxWaitDuration: 0ms }
```

- **TimeLimiter** — the non-negotiable baseline: no call may wait unboundedly.
  Works on `CompletableFuture` returns.
- **Retry** — transient errors only, exponential backoff (1s→2s→4s), add
  jitter. Only for **idempotent** operations (a timed-out POST may have
  succeeded! — the duplicate-charge classic; idempotency keys,
  [task 11](04-kafka/02-reliability-dlt-idempotency.md)).
- **RateLimiter** — N permits per refresh period; excess fails fast
  (`timeoutDuration: 0`) → 429 to callers. Protects *you*.
- **Bulkhead** — max concurrent calls to one dependency (semaphore; or a
  dedicated thread pool variant) — the formalization of "one pool per concern"
  from [task 8](03-async-and-scheduling/01-thread-pools-completablefuture.md). Inventory hanging can hold at most 10
  threads, never all 200.

### 3. Stacking and fallbacks

```java
@CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
@Retry(name = "inventoryService")
@TimeLimiter(name = "inventoryService")
public CompletableFuture<InventoryResponse> checkInventory(String sku) {
    return CompletableFuture.supplyAsync(() -> inventoryClient.check(sku));
}

public CompletableFuture<InventoryResponse> inventoryFallback(String sku, Exception ex) {
    log.warn("Inventory unavailable (fallback): sku={}", sku);
    return CompletableFuture.completedFuture(InventoryResponse.defaultUnavailable());
}
```

Effective nesting (Resilience4j default aspect order): the TimeLimiter bounds
each attempt, Retry re-attempts, the CircuitBreaker records the aggregate
outcome; the fallback (same signature + exception param) catches what escapes.
It's still Spring AOP — public methods, no self-invocation, the usual proxy
rules.

**Fallback hierarchy** (pick the highest honest one): last-known-good cached
data (say it's stale) → safe default ("shipping estimate unavailable") → queue
for later (accept order, reserve stock async) → feature off. NEVER invented
data — a fake "in stock: 5" is worse than an honest error.

### 4. Operating it

Resilience4j emits Micrometer metrics out of the box ([phase 5](07-observability.md)):
breaker state (alert on OPEN), failure rate, not-permitted call counts, retry
counts, bulkhead saturation. A breaker that's been OPEN for an hour is an
incident, not a mitigation. Actuator `/actuator/circuitbreakers` shows live state.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Walk the three breaker states and what each protects.</summary>

CLOSED: calls flow, outcomes recorded over a sliding window. OPEN (failure
rate over threshold): calls fail instantly without touching the dependency —
protects the caller's threads AND gives the dependency recovery air.
HALF_OPEN (after waitDuration): limited probes; success closes, failure
reopens. Fallback serves throughout.
</details>

<details><summary><b>Q2.</b> Retry vs circuit breaker — why do you want both?</summary>

Retry heals BLIPS (one dropped packet — next attempt succeeds); the breaker
handles OUTAGES (retrying a dead service = adding load × attempts). Together:
retry absorbs noise; sustained failure trips the breaker, which then
suppresses the retries too.
</details>

<details><summary><b>Q3.</b> What is a bulkhead, and what incident does it prevent?</summary>

A cap on concurrent calls to one dependency (semaphore or dedicated pool) —
named for ship compartments. Prevents P1: one slow dependency soaking up every
server thread and taking down unrelated endpoints. Thread-pool-per-concern
made declarative.
</details>

<details><summary><b>Q4.</b> Rules for a correct retry policy (four of them).</summary>

(1) Transient exceptions only — never deterministic 4xx. (2) Exponential
backoff WITH jitter — no synchronized storms. (3) Bounded attempts, then
fallback/DLT. (4) Idempotent operations only — a timed-out write may have
landed; otherwise you need idempotency keys first.
</details>

<details><summary><b>Q5.</b> Rank the fallback strategies and state the one prohibition.</summary>

Stale-but-labeled cache → safe default/degraded response → queue for async
completion → feature disabled. Prohibition: fabricated data presented as real —
a wrong answer is worse than a declared non-answer.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Count-based vs time-based sliding window, and where slow calls fit?</summary>

Count-based: last N calls — stable at low traffic. Time-based: calls in the
last N seconds — better under bursty load (a count window can span hours of
quiet). slowCallRateThreshold + slowCallDurationThreshold let latency
degradation trip the breaker before outright errors do — usually the earlier
signal.
</details>

<details><summary><b>Q7.</b> Why does the TimeLimiter want a CompletableFuture return?</summary>

You can't safely abort a thread stuck in blocking I/O from outside
(cooperative cancellation — 02-concurrency/01); with a future, the caller
completes it exceptionally after the deadline and moves on, optionally
cancelling the underlying task. The work runs on a separate executor the
timeout can walk away from.
</details>

<details><summary><b>Q8.</b> Breaker's been OPEN for 45 minutes. Is the system "working as designed"? What do you do?</summary>

The MITIGATION works; the SYSTEM is in an incident — users are on fallbacks.
OPEN-state duration is an alerting metric: page on breaker OPEN beyond a few
minutes, investigate the dependency, consider manual transition
(forceClosed/half-open via actuator) once it's verifiably healthy.
</details>

<details><summary><b>Q9.</b> How do retries interact with the breaker's failure accounting?</summary>

Each attempt the breaker observes counts — one user request with 3 failed
attempts can contribute 3 failures (trips faster under retry amplification) or,
if the breaker wraps outside retry, 1 aggregated failure. Aspect order
(default: Retry inside CircuitBreaker... verify your version!) changes trip
behavior — a thing to TEST, not assume.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| bulkhead | one executor per concern | `03-async-and-scheduling/01` |
| retry + backoff + give-up | @RetryableTopic → DLT | `04-kafka/02` |
| jitter vs lockstep | livelock needs randomization | `02-concurrency/04` |
| timeout discipline | short transactions / bounded waits | `01-foundations/05` |
| breaker state metrics | Micrometer + alerting | `07-observability` |
| retry needs idempotency | idempotent consumer ledger | `04-kafka/02` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
