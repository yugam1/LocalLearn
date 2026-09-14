# Microservices — Spring Cloud, Saga, Outbox
**⬜ Not Started · plan: Phase 8, Tasks 35–41** — Eureka, Gateway, Config Server, LoadBalancer, Feign, distributed patterns

---

## ⚡ CORE CARD — 60 seconds

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
5. Every network hop needs the [phase 7](09-resilience.md) armor (timeout/retry/breaker/fallback) and correlation-ID propagation ([task 7](01-foundations/07-logging-mdc-correlation-ids.md)) — Feign interceptors do both.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> createOrder: local DB commit succeeds, then Feign calls inventory-service to reserve stock — which times out. The response may or may not have executed server-side. What's the state of the system, and why doesn't try/catch fix it?</summary>

**Unknowable divergence**: your order exists; stock is reserved-or-not — a
timeout means "no information," not "no." try/catch can't restore atomicity
that never existed across two databases. This is THE distributed-transactions
problem. Answers: make reserve idempotent (retry safely with an idempotency
key), or restructure as a saga — publish OrderCreated (via outbox),
inventory reserves when it consumes, compensations handle failure. Design for
uncertainty; don't pretend RPC is a method call.
</details>

<details>
<summary><b>P2.</b> The outbox publisher (<code>@Scheduled</code>, reads unpublished rows → kafkaTemplate.send → mark published) crashes between a successful send and marking the row. What does downstream see, and why is that the accepted design?</summary>

The event is **published twice** after restart (row still unpublished → resent).
That's the deliberate trade: publish-then-mark yields at-least-once — the
alternative (mark-then-publish) risks lost events, which are far worse because
nothing detects them. Duplicates are handled by the idempotent consumers you
already built ([task 11](04-kafka/02-reliability-dlt-idempotency.md)). The whole event architecture
leans on that one ledger.
</details>

<details>
<summary><b>P3.</b> Payment fails midway through the choreography saga (order created, stock reserved). "Rollback" — what actually happens, and what can a user observe in the meantime?</summary>

There is no rollback — the earlier commits are durable. Payment publishes
PaymentFailed; inventory consumes it and runs its **compensation** (release
stock — a new forward transaction); order-service consumes StockReleased and
marks the order CANCELLED. In between, a user can legitimately observe order
PENDING with stock reserved — eventual consistency means intermediate states
are visible, so statuses must be designed for it (PENDING_PAYMENT, not
half-truths).
</details>

---

## 📖 THE STORY

### 1. Eureka — the phone book

Server: `@EnableEurekaServer`, port 8761, doesn't register itself
(`registerWithEureka/fetchRegistry: false`). Clients point at
`defaultZone: http://localhost:8761/eureka/`, register on startup, heartbeat
every 30s; missed heartbeats → instance evicted. Consumers fetch the registry
and cache it (survives brief Eureka outages — AP design: availability over
freshness). `preferIpAddress: true` for container environments where hostnames
lie.

### 2. Spring Cloud Gateway — the smart edge

Routes = predicate + URI + filters:

```yaml
- id: order-service
  uri: lb://order-service          # resolve via Eureka, load-balance
  predicates: [ Path=/api/v1/orders/** ]
  filters:
    - AddRequestHeader=X-Service, gateway
    - name: CircuitBreaker
      args: { name: orderService, fallbackUri: forward:/fallback/orders }
- id: rate-limited
  uri: lb://order-service
  predicates: [ Path=/api/v1/public/** ]
  filters:
    - name: RequestRateLimiter     # token bucket in Redis (fleet-wide!)
      args: { redis-rate-limiter.replenishRate: 10, burstCapacity: 20 }
```

Cross-cutting auth as a `GlobalFilter` (reactive — the gateway is WebFlux):
skip public paths, validate the JWT once, **mutate the request** to inject
`X-User-ID`, forward. Downstream services get identity as a header — cheaper
than N services re-validating, but only safe when the network topology
guarantees all traffic enters through the gateway.

Gateway vs load balancer (interview): LB = L4, dumb connection spreading;
gateway = L7 — routing by path/header, authN, rate limiting, breakers,
transformations, CORS. You typically have both (cloud LB in front of gateway
instances).

### 3. Config Server + refresh

Git-backed (`spring.cloud.config.server.git.uri`, per-app search paths) —
config becomes versioned, reviewed, audited. Clients:
`spring.config.import: configserver:http://localhost:8888` with `fail-fast` +
retry. `@RefreshScope` beans re-read `@Value`s on POST `/actuator/refresh`
(per instance; Spring Cloud Bus broadcasts it fleet-wide). Secrets: encrypted
at rest (`{cipher}...`) or delegated to Vault.

### 4. Calling each other — Feign

```java
@FeignClient(name = "inventory-service",             // Eureka name — no URL
             fallback = InventoryClientFallback.class,
             configuration = FeignClientConfig.class)
public interface InventoryClient {
    @GetMapping("/api/v1/inventory/{sku}")
    InventoryResponse checkStock(@PathVariable String sku);
}
```

Declarative interface → Feign generates the HTTP client, resolves via Eureka,
load-balances (`@LoadBalanced` WebClient is the manual alternative;
RestTemplate is legacy). The two production must-haves:
- **Fallback class** (implements the interface) — degraded answers when
  inventory is down; pairs with Resilience4j.
- **RequestInterceptor** copying `MDC correlationId → X-Correlation-ID` header —
  the distributed trace thread survives the hop.

### 5. The patterns that make it consistent

**Saga** — a distributed workflow as local transactions + compensations.
*Choreography* (used here): services react to each other's events —
OrderCreated → StockReserved → PaymentCompleted → order confirmed; on failure,
PaymentFailed → stock released → order cancelled. Loose coupling, but the flow
lives in nobody's code — trace it via correlation IDs. *Orchestration*: a
coordinator commands each step — explicit flow, central dependency. Versus 2PC:
2PC holds locks across services with a blocking coordinator — availability
poison; sagas commit locally and compensate, accepting visible intermediate
states.

**Outbox** — the fix for [task 10](04-kafka/01-fundamentals-partitions-groups.md)'s dual-write gap:

```java
@Transactional
public OrderResponse createOrder(OrderRequest req) {
    Order order = orderRepo.save(buildOrder(req));
    outboxRepo.save(OutboxEvent.of(order.getId(), "ORDER_CREATED",
                                   toJson(buildEvent(order))));   // SAME tx — atomic
    return mapToResponse(order);
}

@Scheduled(fixedDelay = 1000)         // the relay
public void publishOutboxEvents() {
    outboxRepo.findByPublishedFalse().forEach(e -> {
        kafkaTemplate.send(e.getEventType(), e.getPayload());
        e.setPublished(true);         // publish-then-mark → at-least-once (P2)
    });
}
```

(Debezium/CDC is the log-tailing production variant of the relay.) Event
sourcing & CQRS extend the idea — events as the source of truth, separate read
models — name them as the family this belongs to.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> How does service discovery work end to end, and what breaks without it?</summary>

Instances register name+address with Eureka and heartbeat; clients fetch/cache
the registry; `lb://order-service` resolves to a healthy instance,
client-side load-balanced. Without it: hardcoded URLs that break on every
scale/restart/IP change and need config pushes to grow the fleet.
</details>

<details><summary><b>Q2.</b> What belongs at the gateway, and what's the security precondition for header-based identity?</summary>

Routing, JWT validation (once), rate limiting (Redis token bucket —
fleet-wide), circuit breaking with fallbacks, header injection, CORS.
Precondition for trusting X-User-ID downstream: internal services unreachable
except through the gateway — otherwise anyone can forge the header.
</details>

<details><summary><b>Q3.</b> Saga vs 2PC — mechanics and why microservices pick saga.</summary>

2PC: coordinator gets prepare-votes then commits — holds locks across
services, blocks on coordinator failure, couples availability. Saga: sequence
of independent local commits; failures trigger compensating transactions
(release, refund). No cross-service locks, partition-tolerant — at the price
of eventual consistency and designing compensations.
</details>

<details><summary><b>Q4.</b> State the outbox pattern precisely: what's atomic, what's the delivery guarantee, what's required downstream.</summary>

Business row + event row committed in ONE local transaction (atomicity you
actually have). Relay publishes unpublished rows, then marks them —
crash-between yields duplicates, so delivery is at-least-once and consumers
must be idempotent (eventId ledger). Lost events are impossible short of
losing the DB.
</details>

<details><summary><b>Q5.</b> Choreography vs orchestration sagas?</summary>

Choreography: each service reacts to events — no central brain, loose
coupling, but the workflow is emergent/hard to trace (correlation IDs
essential). Orchestration: a coordinator issues commands and tracks state —
explicit, debuggable, one more service and a coupling point. Small flows →
choreography; long/branching flows → orchestration.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Feign vs WebClient vs RestTemplate?</summary>

Feign: declarative interfaces, Eureka/LB/Resilience4j integration, blocking —
the microservice default for sync calls. WebClient: reactive/non-blocking,
also usable blocking, more code. RestTemplate: legacy/maintenance mode. Bonus
point: Feign fallbacks + interceptors (correlation ID, auth propagation).
</details>

<details><summary><b>Q7.</b> Why does the outbox relay preserve per-aggregate ordering, and what would break it?</summary>

Rows are read in insertion order and keyed (aggregateId → Kafka key → one
partition), so one order's events stay sequenced. Breaks if the relay
parallelizes naively across rows of the same aggregate or drops the key —
then OrderCancelled can beat OrderCreated downstream (task 10's no-key bug
reborn).
</details>

<details><summary><b>Q8.</b> Eureka is down for 10 minutes. What still works?</summary>

Mostly everything: clients cache the registry locally, so existing
resolutions keep working; registrations/evictions pause (new instances
invisible, dead ones linger — some failed calls, mitigated by retries/LB).
Eureka is AP by design: stale availability beats consistent unavailability.
</details>

<details><summary><b>Q9.</b> @RefreshScope — what does it actually do and what's the fleet-wide story?</summary>

Marks a bean lazy-proxied so POST /actuator/refresh destroys and recreates it,
re-resolving @Value/config on next use. Per-instance by default → Spring
Cloud Bus (Kafka/Rabbit) broadcasts the refresh event to every instance at
once.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| outbox closes dual-write | the save-then-publish gap | `04-kafka/01` |
| duplicates → idempotency ledger | at-least-once everywhere | `04-kafka/02` |
| Feign fallback/breaker | Resilience4j patterns | `09-resilience` |
| gateway JWT once | Spring Security filter chain | `06-security` |
| correlation header hop | MDC propagation | `01-foundations/07` |
| saga compensations | no distributed @Transactional | `01-foundations/05` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
