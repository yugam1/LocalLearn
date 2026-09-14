# Observability — Actuator, Micrometer, Tracing
**⬜ Not Started · plan: Phase 5, Tasks 24–27** (logging = 01-foundations/07 ✅) — Health checks, Prometheus metrics, Zipkin tracing

---

## ⚡ CORE CARD — 60 seconds

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

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> You add a custom KafkaHealthIndicator and wire /actuator/health as the K8s <b>liveness</b> probe. Kafka has a 5-minute outage. What does Kubernetes do to your (perfectly healthy) order-service pods?</summary>

**Kills and restarts all of them, repeatedly.** Liveness failure = "the process
is broken, restart it." Kafka being down isn't fixed by restarting your pods —
now you have the Kafka outage PLUS a restart storm PLUS cold caches. Dependency
health belongs in **readiness** (stop routing traffic) or plain monitoring.
Liveness should test only "is this JVM stuck" — the narrower the better.
</details>

<details>
<summary><b>P2.</b> To "measure average latency" someone records <code>Timer</code> without histograms and averages it across 10 pods. p99 on one pod is 4s, the mean shows 80ms. Why is the dashboard lying, and what config fixes it?</summary>

Averages bury tails — 1% of requests at 4s vanish inside 99% at 40ms, and you
also **cannot average or combine percentiles computed per-pod**. Fix:
`percentiles-histogram: http.server.requests: true` — pods export raw buckets,
Prometheus aggregates them, `histogram_quantile(0.99, ...)` computes the true
fleet-wide p99. Users experience percentiles, not means.
</details>

<details>
<summary><b>P3.</b> A request flows order-service → Kafka → inventory-service. With Micrometer Tracing + Zipkin on both services, will the inventory span appear under the same trace? What physically carries the link?</summary>

Yes — the producer injects trace context (`traceparent`/B3 headers) as **Kafka
message headers**; the consumer extracts them and continues the trace. Same
trick as your manual correlationId in the event payload (task 7), standardized
and automatic. If a hop strips headers (a hand-rolled client, a bridge), the
trace silently splits in two — that's the first thing to check when traces
"end" mid-flow.
</details>

---

## 📖 THE STORY

### 1. Actuator — the ops socket

Expose deliberately (`health,info,metrics,prometheus,loggers`), not `*` — `/env`
and `/beans` leak config to anyone who can reach them; in prod they sit behind
auth ([phase 4](06-security.md)). Gems: `/loggers` can flip a logger
to DEBUG **at runtime** (no redeploy) — the production-debugging cheat code;
`show-details: when-authorized` keeps component details from anonymous eyes.

**Health = aggregation of HealthIndicators** (built-in: datasource, disk,
Kafka...; custom = implement `HealthIndicator`, return
`Health.up()/.down().withDetail(...)`). Overall status = worst component.
K8s probes: `probes.enabled: true` gives `/actuator/health/liveness` (process
alive — restart on failure) and `/readiness` (accepting traffic — drain on
failure). Choose each indicator's group deliberately (P1).

### 2. Micrometer — instrument once, publish anywhere

Add `micrometer-registry-prometheus` → `/actuator/prometheus` appears, already
full of free metrics: HTTP server timings, JVM heap/GC/threads, **HikariCP**
(active/pending/timeouts — the pool saturation story from
[task 6](01-foundations/06-n-plus-one-hikaricp-tuning.md), now graphable), Kafka client lag.

Custom metrics for what the business cares about:

```java
Counter created = Counter.builder("orders.created.total")
        .tag("service", "order-service").register(meterRegistry);
Timer processing = Timer.builder("orders.processing.duration")
        .publishPercentileHistogram().register(meterRegistry);
AtomicInteger active = meterRegistry.gauge("orders.active", new AtomicInteger(0));

return processing.record(() -> {
    try { ...; created.increment(); active.incrementAndGet(); ... }
    catch (Exception e) { failed.increment(); throw e; }
});
```

Naming: dot-separated, unit-suffixed (`.total`, `.duration`); **low-cardinality
tags only** — tag by endpoint or status, never by userId/orderId (each tag value
= a new time series; unbounded tags melt Prometheus).

### 3. Prometheus + Grafana — pull, store, alert

Prometheus **scrapes** `/actuator/prometheus` every 15s (pull model — targets
stay dumb, discovery lives in Prometheus). Dashboard staples:

| Panel | Metric |
|---|---|
| RPS + error rate | `http_server_requests_seconds_count` by status |
| p99 latency | `histogram_quantile(0.99, rate(..._bucket[5m]))` |
| JVM | heap used, GC pauses, live threads |
| Pool health | `hikaricp_connections_active` / `_pending` / `_timeout_total` |
| Kafka | `..._records_lag_max` |
| Business | `orders_created_total`, `orders_active`, order p99 |

Alert on user-visible symptoms: 5xx rate > 1%, p99 > 2s, `hikaricp_pending > 5`
(the outage predictor), consumer lag > threshold.

### 4. Distributed tracing — the waterfall

`micrometer-tracing-bridge-brave` + `zipkin-reporter-brave`; Zipkin via
`docker run -p 9411:9411 openzipkin/zipkin`. Auto-instrumented: HTTP
server/client, JPA, Kafka producer+consumer, @Async. Each unit of work = a
**span** (name, duration, tags); spans share a **traceId**; Zipkin renders the
waterfall — "of this 800ms request, 650ms was inventory-service's DB call."

Manual enrichment: `tracer.currentSpan().tag("order.customer", email)`.
Propagation = headers (`traceparent` / `X-B3-TraceId`...) injected into
RestTemplate/WebClient/Feign calls and Kafka messages automatically. Trace IDs
also land in the MDC → every log line carries them → logs↔traces cross-linkable.

Sampling: dev 1.0; prod 0.01–0.1 (head-based). Tail-based sampling (keep slow/
error traces, drop boring ones) needs a collector like the OTel collector —
name-drop it in interviews.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> The three observability signals — what question does each answer, and the cost profile of each?</summary>

Metrics: aggregate "how many/how fast" — cheap, always-on, alertable, no
per-request detail. Traces: per-request "where did time go across services" —
sampled because of volume. Logs: arbitrary detail "what happened" — expensive
to store/search, greppable via correlation/trace IDs. You need all three; they
cross-reference via trace IDs in logs.
</details>

<details><summary><b>Q2.</b> Counter vs Gauge vs Timer — one example each from order-service.</summary>

Counter (monotonic events): orders.created.total, orders.failed.total. Gauge
(current level): orders.active, pool active connections. Timer (duration
distribution + percentiles): orders.processing.duration, HTTP request time.
Interview differentiator: Timer with publishPercentileHistogram so percentiles
aggregate across instances.
</details>

<details><summary><b>Q3.</b> Liveness vs readiness — definition, consequence of failure, and the classic misconfiguration.</summary>

Liveness: "process irrecoverably broken?" — failure = kill + restart. Readiness:
"can it serve right now?" — failure = removed from load balancing, pod lives.
Classic mistake: external dependencies in liveness → dependency blip becomes a
fleet-wide restart storm. Dependencies belong in readiness at most.
</details>

<details><summary><b>Q4.</b> What is Micrometer, and what do you get for free in Spring Boot?</summary>

A vendor-neutral instrumentation facade (SLF4J-for-metrics): code once, choose
backend by registry dependency. Boot auto-instruments HTTP server requests,
JVM memory/GC/threads, HikariCP, Kafka clients, @Scheduled — custom metrics
are only for business concepts.
</details>

<details><summary><b>Q5.</b> How does a trace survive crossing Kafka?</summary>

The producer interceptor injects trace context into message HEADERS; the
consumer extracts and continues the same traceId with a new span. Async hops
appear in the same waterfall. Breaks silently if any hop drops headers.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why can't you average p99s from 10 pods, and what's the correct pipeline?</summary>

A percentile is a property of a distribution, not a linear statistic — the
fleet p99 depends on the merged distribution, not the mean of per-pod p99s.
Correct: export histogram buckets per pod, sum bucket rates in Prometheus,
histogram_quantile over the merged buckets.
</details>

<details><summary><b>Q7.</b> Tag cardinality — why does tagging metrics by orderId take Prometheus down?</summary>

Every unique tag-value combination is a separate time series held in memory
and on disk. Unbounded values (order IDs, user IDs) = millions of series =
scrape slowdowns and OOM. Bound tags to enums: endpoint, method, status,
outcome. Per-entity detail belongs in traces/logs.
</details>

<details><summary><b>Q8.</b> Prod sampling strategy — head vs tail, and when 1% is wrong.</summary>

Head-based (decide at trace start, probability 0.01–0.1): cheap, unbiased,
misses rare failures. Tail-based (decide after completion): keep all
errors/slow traces, drop happy-path noise — needs a buffering collector.
Sample high-value flows (payments) at higher rates; 1% of a low-traffic
critical path may be zero traces when you need them.
</details>

<details><summary><b>Q9.</b> /actuator/loggers — the production superpower and its guardrail.</summary>

POST to /actuator/loggers/com.ecommerce.orderservice with {"configuredLevel":
"DEBUG"} flips logging live — no redeploy, scoped to one logger. Guardrails:
endpoint behind auth, remember to set it back (or it floods disk), and never
root-level DEBUG in prod.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| traceId in MDC/logs | correlation IDs done by hand | `01-foundations/07` |
| hikaricp_pending alert | pool exhaustion chain | `01-foundations/06` |
| consumer lag metric | lag = THE consumer health signal | `04-kafka/02` |
| readiness + graceful shutdown | zero-downtime deploys | `16-production-hardening` |
| Micrometer Counter internals | LongAdder | `02-concurrency/03` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
