# Logging — SLF4J, MDC & Correlation IDs
**✅ Completed · plan: Phase 1, Task 7** — Log levels, MDC propagation, Logback config, async appenders

---

## ⚡ CORE CARD — 60 seconds

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

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> MDCFilter sets correlationId; the request calls an <code>@Async</code> method which logs. What appears in the async thread's <code>[%X{correlationId}]</code> field, and why?</summary>

**Empty.** MDC is backed by a ThreadLocal; the executor's worker thread never had
anything put into *its* map. Fix: `MDCTaskDecorator` — capture
`MDC.getCopyOfContextMap()` on the submitting thread, `setContextMap` inside the
wrapped Runnable, `clear()` in finally — set via `executor.setTaskDecorator(...)`.
</details>

<details>
<summary><b>P2.</b> A filter sets MDC values but has no <code>finally { MDC.clear(); }</code>. Tomcat reuses threads. What bug appears in the logs, and why is it nasty?</summary>

**Context bleeding:** a later request served by the same pooled thread logs the
*previous* request's correlationId/userId — logs confidently attribute actions to
the wrong user. Nasty because everything looks normal; the data is just wrong,
and it only happens under thread reuse, so dev (low traffic) rarely shows it.
</details>

<details>
<summary><b>P3.</b> <code>log.debug("Order: " + order)</code> vs <code>log.debug("Order: {}", order)</code> in prod where root level is INFO. What work does each line do?</summary>

Concatenation version: builds the full string (calls `order.toString()` —
possibly touching lazy collections!) and *then* discards it — every call.
Parameterized version: checks the level, sees DEBUG disabled, returns —
`toString()` never runs. Same output when enabled; wildly different cost when
disabled.
</details>

---

## 📖 THE STORY

### 1. Levels are an alerting contract, not decoration

TRACE/DEBUG — dev only. **INFO** — business events worth an audit trail ("order
created, id=42"). **WARN** — something unexpected that the system *handled*
(retry succeeded, stock low, deprecated endpoint hit) — reviewed periodically.
**ERROR** — failed and needs a human — this is what pager alerts key on, so an
ERROR that nobody needs to act on is an alert you just trained people to ignore.

### 2. MDC — ambient context for every log line

`MDC.put("correlationId", id)` once; every subsequent log line on that thread
includes it via the pattern `%X{correlationId:-}`. The filter is the single
entry point:

```java
@Component @Order(1)
public class MDCFilter implements Filter {
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) ... {
        try {
            String cid = httpReq.getHeader("X-Correlation-ID");        // honor upstream's id
            if (cid == null) cid = "REQ-" + UUID.randomUUID();          // or mint one
            MDC.put("correlationId", cid);
            httpRes.setHeader("X-Correlation-ID", cid);                 // echo downstream/back
            MDC.put("ipAddress", clientIp(httpReq));                    // X-Forwarded-For aware
            long start = System.currentTimeMillis();
            try { chain.doFilter(req, res); }
            finally {
                long ms = System.currentTimeMillis() - start;
                log.info("← {} {} {}ms [{}]", method, uri, ms, status);
                if (ms > 1000) log.warn("SLOW REQUEST: {}ms on {} {}", ms, method, uri);
            }
        } finally { MDC.clear(); }                                      // NON-NEGOTIABLE
    }
}
```

Design details that matter: accept an incoming header before generating (so the
gateway's ID survives), echo it on the response (client support tickets can
quote it), and the double-finally (timing log + clear).

### 3. Crossing execution boundaries

The ID must survive three hops:
- **Threads** — `MDCTaskDecorator` (P1) on every `ThreadPoolTaskExecutor`.
- **HTTP to other services** — send `X-Correlation-ID` header on outbound calls.
- **Kafka** — `event.setCorrelationId(MDC.get("correlationId"))` at publish;
  consumer does `MDC.put(...)` (and clear) around processing.

This is manual distributed tracing; Micrometer Tracing/Zipkin ([phase 5](../07-observability.md))
automates the same idea with traceId/spanId.

### 4. Logback architecture — appenders, rolling, async

`logback-spring.xml` (the `-spring` suffix enables `<springProfile>` blocks):

- **CONSOLE** — human pattern with `%X{correlationId:-}`, colored, dev.
- **FILE_ALL** — `RollingFileAppender` + `SizeAndTimeBasedRollingPolicy`
  (`100MB`/file, 30 days, `totalSizeCap 3GB` — bounded disk, no 3 a.m. full-disk page).
- **FILE_ERROR** — same, filtered by `ThresholdFilter(ERROR)`, longer retention
  (90d), `%ex{full}` stack traces. Errors get their own file so triage doesn't grep the firehose.
- **ASYNC_ALL** — `AsyncAppender` wrapping FILE_ALL: the request thread enqueues
  to a 512-slot in-memory queue and moves on; a background thread does disk I/O.
  `discardingThreshold: 0` = never silently drop (the alternative drops
  TRACE–INFO when the queue is 80% full — choose consciously: latency vs completeness).
- **Named loggers** — `PERFORMANCE_LOGGER`, `AUDIT_LOGGER` with
  `additivity="false"` (don't ALSO flow to root) route special streams to their
  own destinations.
- **Profiles** — prod: app INFO, `org.hibernate.SQL` WARN (show-sql is a dev toy).

### 5. The performance aspect — logging as a cross-cutting concern

```java
@Aspect @Component
public class PerformanceLoggingAspect {
    private static final Logger PERF = LoggerFactory.getLogger("PERFORMANCE_LOGGER");

    @Around("execution(* com.ecommerce.orderservice.service..*(..))")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long ms = System.currentTimeMillis() - start;
            if (ms > 1000) PERF.warn("SLOW: method={}, duration={}ms", sig(pjp), ms);
            return result;
        } catch (Exception e) {
            PERF.error("FAILED: method={}, duration={}ms", sig(pjp),
                       System.currentTimeMillis() - start);
            throw e;    // observe, never swallow
        }
    }
}
```

One aspect, every service method timed — no timing code in business logic.
(This is the AOP preview; the machinery — proxies again! — is [phase 9](../11-aop-proxies.md).)

### 6. What never goes in a log

Passwords, JWTs, full card numbers, full emails in bulk. Masking helpers:
`maskEmail() → j***n@example.com`, `card.substring(len-4)`. Logs get shipped,
indexed, retained, and read by more people than your database ever will be —
treat them as a **less** secure store, not more.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> What is MDC, what backs it, and what's the classic production leak?</summary>

Mapped Diagnostic Context — a per-thread map whose entries the pattern layout
injects into every log line (`%X{key}`). Backed by ThreadLocal. Leak: no
`MDC.clear()` in finally → pooled thread carries request A's context into
request B → logs attribute actions to the wrong user/request.
</details>

<details><summary><b>Q2.</b> Walk the correlation ID end to end: browser → order-service → @Async → Kafka → inventory-service.</summary>

Filter reads `X-Correlation-ID` or mints `REQ-uuid` → MDC.put + response header.
@Async: TaskDecorator copies the context map onto the worker thread. Kafka:
producer copies MDC value into the event payload; consumer MDC.puts it back
(clears after). Other services honor the incoming header. Result: one grep
reconstructs the distributed flow.
</details>

<details><summary><b>Q3.</b> Why parameterized logging, and where does the exception argument go?</summary>

`log.info("x={}", x)` defers all formatting until the level check passes —
disabled levels cost ~nothing, and no accidental `toString()` side effects
(lazy collections!). The Throwable goes last, *outside* the placeholders:
`log.error("failed: id={}", id, ex)` → full stack trace.
</details>

<details><summary><b>Q4.</b> Why async appenders — and what's the trade-off knob?</summary>

Synchronous appenders make every log call a disk write on the request thread —
logging becomes request latency. AsyncAppender: enqueue in memory, background
thread drains. Knob: `discardingThreshold` — 0 keeps everything (may block when
queue fills); default drops sub-WARN under pressure (fast but lossy).
</details>

<details><summary><b>Q5.</b> Define the INFO/WARN/ERROR contract in terms of operations.</summary>

INFO: expected business events — the audit trail. WARN: anomaly the system
handled (retries, fallbacks, low stock) — dashboards/periodic review. ERROR:
unhandled/needs human — alerting keys on it, so ERROR-that's-not-actionable is
alert fatigue by design.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why might a log statement itself trigger a LazyInitializationException or an N+1?</summary>

`log.debug("order={}", order)` calls `order.toString()` when DEBUG is on — if
Lombok's toString includes the items collection, that's a lazy load from
wherever the log line sits (or an LIE outside a session). Another reason for
`@ToString(exclude = "items")` and parameterized logging.
</details>

<details><summary><b>Q7.</b> What does additivity="false" do and when do you need it?</summary>

Stops a named logger's events from ALSO propagating up to root's appenders.
Needed for special streams (AUDIT_LOGGER, PERFORMANCE_LOGGER) that must go only
to their own file — without it every audit line appears twice (own file + root's).
</details>

<details><summary><b>Q8.</b> Why cap rolling logs three ways (maxFileSize, maxHistory, totalSizeCap)?</summary>

Each bounds a different failure: maxFileSize keeps single files greppable/rotatable;
maxHistory bounds retention time (compliance + cleanup); totalSizeCap is the hard
disk-space ceiling when traffic spikes make daily files huge. Without the cap, a
log storm fills the disk and takes the service down — logging must never be the outage.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| ThreadLocal MDC gotcha | thread confinement / context loss | `03-async-and-scheduling/01` (MDC + @Async), `02-concurrency/01` |
| correlation ID | traceId/spanId in distributed tracing | `07-observability` |
| performance aspect | AOP proxies (same machinery as @Transactional) | `11-aop-proxies`, `01-foundations/05` |
| async appender queue | bounded queues + rejection/discard policy | `03-async-and-scheduling/01` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
