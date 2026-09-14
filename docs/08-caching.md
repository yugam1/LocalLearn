# Caching — @Cacheable, Redis, Caffeine
**⬜ Not Started · plan: Phase 6, Tasks 28–30** — Cache abstraction, TTLs, multi-level caching, the classic cache failure modes

---

## ⚡ CORE CARD — 60 seconds

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

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> <code>getOrderById</code> is @Cacheable("orders"). <code>updateOrder</code> saves to the DB but has no cache annotation (TTL is 30 min). A user edits their order, then refreshes the page. What do they see, and for how long?</summary>

**The old order, for up to 30 minutes.** The update changed the DB but the
cache still holds the pre-edit copy under the same key; every read is a hit, so
the method (and DB) are never consulted. Fix: `@CacheEvict(value="orders",
key="#id")` (or @CachePut with the fresh result) on EVERY mutating path — plus
`@Caching` for multi-cache invalidation (evict the summary list too).
</details>

<details>
<summary><b>P2.</b> Attackers request <code>GET /orders/999999999</code> (nonexistent) 10k times/sec. The method is @Cacheable with <code>unless = "#result == null"</code>. What happens to your database, and which config choice caused it?</summary>

Every request is a cache miss that **hits the DB** — `unless = "#result ==
null"` (and `disableCachingNullValues`) means "never store the not-found
answer," so the cache can't absorb them. This is **cache penetration**. Fixes:
cache the null/absent marker with a short TTL, or a Bloom filter in front to
reject impossible ids before the DB.
</details>

<details>
<summary><b>P3.</b> Redis-backed cache with GenericJackson2JsonRedisSerializer. You rename a field in OrderResponse and deploy while Redis retains 30 minutes of old entries. First read of an old entry — what happens?</summary>

Deserialization error (or silently null field, depending on mapper config) —
the cache holds JSON of the OLD shape, and your new class can't read it.
Cached DTOs are a **persistence format with a schema**: on shape changes, bump
the cache name/key prefix (versioned keys: `orders:v2:`) or flush on deploy.
Also why entities don't belong in caches — DTOs only.
</details>

---

## 📖 THE STORY

### 1. The abstraction — annotations on a proxy

`@EnableCaching` + a `CacheManager` bean; the annotations declare *policy*:

```java
@Cacheable(value = "orders", key = "#id", unless = "#result == null")
public OrderResponse getOrderById(Long id) { ... }

@CachePut(value = "orders", key = "#result.id")        // run + refresh
public OrderResponse createOrder(OrderRequest req) { ... }

@Caching(evict = { @CacheEvict(value = "orders", key = "#id"),
                   @CacheEvict(value = "orderSummaries", allEntries = true) })
public void deleteOrder(Long id) { ... }
```

SpEL keys (`#id`, `#result.id`, `#req.customerEmail`); `unless` filters what
gets stored. Backend-agnostic: the same annotations run against Caffeine,
Redis, or a simple map (tests).

### 2. Redis — the shared L2

```java
RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(30))
    .disableCachingNullValues()
    .serializeValuesWith(SerializationPair.fromSerializer(
        new GenericJackson2JsonRedisSerializer()));
RedisCacheManager.builder(factory).cacheDefaults(config)
    .withCacheConfiguration("orders",   config.entryTtl(Duration.ofMinutes(10)))
    .withCacheConfiguration("products", config.entryTtl(Duration.ofHours(1)))
    .build();
```

Per-cache TTLs encode volatility (orders change, product catalogs less so).
Redis buys you: one cache for N pods (no per-pod inconsistency), survival
across restarts, and primitives beyond caching — `setIfAbsent(key, v, ttl)` is
a **distributed lock** (SET NX EX — ShedLock's Redis provider is exactly this;
production locks want Redisson or token-checked release so you can't delete
someone else's lock).

### 3. Caffeine and the two-level pattern

Caffeine = in-JVM, sub-microsecond, size-bounded (`maximumSize`,
`expireAfterWrite`), W-TinyLFU eviction — but per-pod and gone on restart.

```
read: L1 hit → return (ns)
      L1 miss → L2 Redis hit → promote to L1 → return (~1ms)
      L2 miss → DB → write Redis, write Caffeine → return
```

The catch: **L1s go stale independently** — pod A updates and evicts Redis, pod
B's Caffeine still holds the old value until its (short!) L1 TTL expires. Rules
of thumb: L1 TTL ≪ L2 TTL (seconds vs minutes), or broadcast invalidations
(Redis pub/sub). Consistency is why multi-level is a *measured* optimization,
not a default.

### 4. The three named failure modes

| Failure | Shape | Fix |
|---|---|---|
| **Stampede** | hot key expires → 1000 concurrent misses → DB flattened | lock/single-flight (one loads, rest wait — `computeIfAbsent` at L1, [02-concurrency/04](02-concurrency/04-locks-deadlock-conditions.md)); early/probabilistic refresh before TTL |
| **Penetration** | keys that never exist bypass the cache every time | cache the negative result briefly; Bloom filter |
| **Avalanche** | many keys share one TTL → synchronized mass expiry | jitter the TTLs (30min ± random few min) |

Same jitter logic as retry backoff ([task 11](04-kafka/02-reliability-dlt-idempotency.md)) — synchronized
behavior is the enemy everywhere.

### 5. Strategy answers interviewers want

**Cache-aside** (this whole doc): app manages the cache next to the DB — simple,
but eviction discipline is on you. Write-through/write-behind push writes via
the cache — rarer in Spring apps. **What to cache**: read-heavy, tolerant of
slight staleness, expensive to compute. **What never to cache**: anything whose
staleness costs money without compensation (live stock counts feeding
purchase decisions — that's what DB locking was for, [task 5](01-foundations/05-transactions-isolation-locking.md)).

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> @Cacheable vs @CachePut vs @CacheEvict — execution semantics of each.</summary>

@Cacheable: hit → method skipped, cached value returned; miss → execute +
store. @CachePut: ALWAYS executes, result replaces the cache entry (writes).
@CacheEvict: removes key (or allEntries) — invalidation on mutation/delete.
@Caching bundles several on one method.
</details>

<details><summary><b>Q2.</b> Caffeine vs Redis — and why use both levels?</summary>

Caffeine: in-process, nanoseconds, per-pod, lost on restart, bounded by
size/TTL. Redis: network hop (~1ms), shared by all pods, survives restarts.
L1+L2: hot keys at memory speed, shared truth behind them. Cost: L1 staleness
across pods — keep L1 TTL short or broadcast invalidations.
</details>

<details><summary><b>Q3.</b> Stampede vs penetration vs avalanche — one line + one fix each.</summary>

Stampede: hot key expires, N callers rebuild at once → single-flight lock or
early refresh. Penetration: nonexistent keys always miss → cache negatives
briefly / Bloom filter. Avalanche: shared TTL mass-expiry → jitter TTLs.
</details>

<details><summary><b>Q4.</b> Design the TTL scheme for order-service caches.</summary>

By volatility: stock/prices seconds; order/product listings minutes (orders:
10m); reference data (categories, currencies) hours; plus write-path eviction
everywhere so TTL is only the backstop. And jitter on high-population caches.
</details>

<details><summary><b>Q5.</b> How does Redis SET NX EX become a distributed lock, and its two classic bugs?</summary>

setIfAbsent(key, val, ttl): atomic "claim if unclaimed with an expiry lease."
Bugs: (1) holder outlives TTL → second holder + the first later deletes the
second's lock (fix: unique token, check-before-delete / Lua); (2) no TTL →
crash = permanent lock. Same lease logic as ShedLock's lockAtMostFor.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why does @Cacheable on a method called via `this.` return uncached results?</summary>

Cache interception lives on the Spring proxy; self-invocation is a direct call
that never crosses it. Fourth appearance of the proxy trap (@Transactional,
@Async, @PreAuthorize, now caching) — at this point it should be reflex.
</details>

<details><summary><b>Q7.</b> What are the serialization consequences of caching in Redis?</summary>

Values persist as bytes (here JSON) with an implicit schema: class shape
changes break old entries (version the cache keys or flush on deploy); JPA
entities with lazy proxies/back-references don't serialize sanely — cache
DTOs; and GenericJackson2 stores type info, coupling entries to class names.
</details>

<details><summary><b>Q8.</b> When is caching the WRONG answer?</summary>

When staleness costs correctness money (inventory allocation, balances —
that's locking/transactions territory); write-heavy data (eviction churn eats
the benefit); when the query is already indexed-fast (cache adds a failure
mode, not speed); and as a band-aid over an N+1 you haven't fixed (task 6
first, cache second).
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| single-flight stampede fix | computeIfAbsent per-bin lock | `02-concurrency/04` |
| SET NX EX lease | ShedLock lockAtMostFor | `03-async-and-scheduling/02` |
| TTL jitter | retry backoff jitter | `04-kafka/02`, `09-resilience` |
| staleness vs correctness | optimistic/pessimistic locking | `01-foundations/05` |
| cache hit-rate metrics | Micrometer gauges | `07-observability` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
