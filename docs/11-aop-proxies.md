# AOP — Aspects, Pointcuts, Custom Annotations
**⬜ Not Started · plan: Phase 9, Tasks 42–43** — @Around advice, pointcut expressions, @LogExecutionTime, @Auditable

---

## ⚡ CORE CARD — 60 seconds

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

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> An @Around advice logs the method name and — because the developer forgot — never calls <code>pjp.proceed()</code>, just <code>return null;</code>. The pointcut matches the whole service layer. What does the application do?</summary>

Every service method **stops executing entirely** — controllers receive null
from createOrder, getOrderById, everything; no orders are saved, no exceptions
thrown. @Around REPLACES the method call; proceed() is the only thing that
invokes the real code. This failure is silent and total — reason #1 to test
aspects, not just business logic.
</details>

<details>
<summary><b>P2.</b> @Auditable sits on <code>deleteOrder(Long id)</code>. Another method in the same service calls <code>this.deleteOrder(42L)</code> during a bulk cleanup. Does an audit record get written? Would compile-time AspectJ behave differently?</summary>

No record — self-invocation bypasses the proxy, so the audit advice never
runs. For an AUDIT trail, that's not an inconvenience, it's a compliance hole:
the bulk path deletes without a trace. Spring AOP (runtime proxies) can't fix
it; AspectJ compile/load-time **weaving** rewrites the bytecode itself, so
even `this.` and private calls are advised — the heavyweight escape hatch.
</details>

<details>
<summary><b>P3.</b> Both PerformanceLoggingAspect (@Around) and @Transactional wrap the same service method. Sketch the possible nesting orders — does timing include transaction begin/commit or not, and how do you control it?</summary>

Two onion orders: timing-outside-tx measures begin+commit+method (what the
caller feels); tx-outside-timing measures only the method body. Spring decides
by aspect **order** (`@Order` on the aspect / Ordered interface;
@Transactional's order is configurable, default lowest precedence = innermost…
actually outermost wrapping varies — the point is it's DEFINED, not random).
If the distinction matters — and for "why is commit slow?" it does — set
@Order explicitly and verify with a log line.
</details>

---

## 📖 THE STORY

### 1. Vocabulary, then the reveal

**Aspect** (the class) declares **advice** (the code) at **pointcuts** (the
predicate); each interception is a **join point**. Enable with
`spring-boot-starter-aop`. The reveal that reframes half this curriculum:
@Transactional, @Cacheable, @Async, @PreAuthorize, @RateLimiter are all this
exact machinery — one proxy chain per bean, advice stacked in order. Their
shared rules (public, no self-invocation) aren't quirks; they're proxy physics.

### 2. Pointcut language — the greatest hits

```java
execution(* com.ecommerce.orderservice.service..*(..))   // layer sweep (.. = subpackages)
@annotation(com.ecommerce.annotation.Auditable)          // opt-in marker
within(com.ecommerce.orderservice.service.impl.OrderServiceImpl)  // one class
execution(* com.ecommerce.service..*(..)) && !execution(* com.ecommerce.service..get*(..))
```

Keep them DRY in a `CommonPointcuts` class: named `@Pointcut("...")` methods
(`serviceLayer()`, `repositoryLayer()`, `transactionalMethod()`) referenced as
`@Around("CommonPointcuts.serviceLayer()")`.

### 3. The five advice types (one to actually use)

`@Before` (can't stop the call), `@After` (finally-like), `@AfterReturning`
(sees the result), `@AfterThrowing` (sees the exception), and **`@Around`** —
the one that wraps:

```java
@Around("CommonPointcuts.serviceLayer()")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    try {
        Object result = pjp.proceed();          // ← the actual method
        record(pjp, System.currentTimeMillis() - start, "OK");
        return result;                          // ← forward the result!
    } catch (Exception e) {
        record(pjp, System.currentTimeMillis() - start, "FAILED");
        throw e;                                // ← observe, never swallow
    }
}
```

@Around subsumes the other four — in practice you write @Around for
timing/audit and @AfterThrowing occasionally for error translation.

### 4. Production aspect #1 — performance logging

Two pointcuts, two thresholds: service layer warns over 1000ms; repository
layer warns over 500ms ("SLOW QUERY"). Output goes to the named
`PERFORMANCE_LOGGER` ([task 7](01-foundations/07-logging-mdc-correlation-ids.md)'s additivity="false" logger),
optionally stashing `lastMethodDurationMs` into MDC. This is exactly what
Micrometer's `@Timed` does generically — knowing both, and saying "same
mechanism," is the senior move.

### 5. Production aspect #2 — annotation-driven

The recipe, once:

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {
    String value() default "";
    long warnThresholdMs() default 1000;       // config IN the annotation
}

@Around("@annotation(logExecutionTime)")       // param name binds the annotation
public Object logTime(ProceedingJoinPoint pjp, LogExecutionTime logExecutionTime)
        throws Throwable {
    long start = System.currentTimeMillis();
    Object result = pjp.proceed();
    long ms = System.currentTimeMillis() - start;
    if (ms > logExecutionTime.warnThresholdMs()) log.warn("[SLOW] ...");
    return result;
}
```

`@Auditable(action = "ORDER_DELETE", logArgs = false)` follows the same shape:
advice reads `MDC.get("userId")`, writes ACTION/SUCCESS/FAILED lines to
`AUDIT_LOGGER`, rethrows on failure. Two judgment calls interviewers like:
`logArgs` defaults to **false** (args may contain PII — [task 7](01-foundations/07-logging-mdc-correlation-ids.md)'s
never-log list), and audit logging must not *break* the operation it audits
(what if the audit write fails? decide explicitly).

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Define aspect, pointcut, advice, join point — one line each with an example from this project.</summary>

Aspect: the class packaging a cross-cutting concern (PerformanceLoggingAspect).
Pointcut: predicate choosing methods (`execution(* ..service..*(..))`).
Advice: the code that runs (@Around timing block). Join point: one intercepted
execution (this call to createOrder).
</details>

<details><summary><b>Q2.</b> @Around vs the other advice types — and its two contract obligations.</summary>

@Around wraps the call: code before AND after, can short-circuit, modify
args/result, measure. Obligations: call proceed() (or the method never runs)
and return/propagate its result and exceptions (or you silently null-out /
swallow the layer). @Before can't prevent execution; @AfterReturning/Throwing
only observe one outcome.
</details>

<details><summary><b>Q3.</b> Why exactly can't Spring AOP advise private methods or self-invocations?</summary>

Spring AOP = runtime proxies (JDK dynamic for interfaces, CGLIB subclassing
otherwise). Advice lives on the proxy object; only calls entering THROUGH the
proxy are intercepted. this.method() and private calls happen inside the
target — the proxy never sees them. AspectJ weaving (bytecode modification)
removes the limit at build complexity cost.
</details>

<details><summary><b>Q4.</b> "Is @Transactional AOP?" — give the full-credit answer.</summary>

Yes — a transaction-management aspect: an @Around-equivalent advice acquires a
connection/begins, proceeds, commits or rolls back based on the exception. Same
proxy machinery as @Cacheable/@Async/@PreAuthorize, hence the identical
self-invocation and visibility rules. That mapping is why learning AOP
retroactively explains four earlier phases.
</details>

<details><summary><b>Q5.</b> Recipe for a custom annotation-driven aspect, end to end.</summary>

(1) @interface with @Target(METHOD) + @Retention(RUNTIME), config as
attributes with defaults. (2) @Aspect @Component with
@Around("@annotation(paramName)") binding the annotation instance as a method
parameter. (3) Advice reads attributes for behavior. (4) Annotate business
methods — zero infrastructure code at call sites.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> When do you choose execution() sweeps vs @annotation() opt-in?</summary>

Sweep for truly universal, cheap concerns (perf logging at DEBUG). Opt-in for
anything costly, sensitive, or policy-like (auditing, custom retries) — the
annotation documents intent at the call site and prevents surprise
interception of new methods that happen to match a package pattern.
</details>

<details><summary><b>Q7.</b> How do multiple aspects on one method order themselves, and why care?</summary>

@Order/Ordered on the aspects — lower value = outer wrapper. It changes
semantics: timing outside vs inside the transaction; security check before vs
after rate limiting; a cache in front of vs behind auth (bug!). Explicitly
order any aspect whose position changes meaning.
</details>

<details><summary><b>Q8.</b> Design question: audit write fails but the business operation succeeded — what should happen?</summary>

Decide, don't default: for compliance-critical audits, fail the operation
(audit in the same transaction — REQUIRES_NEW is WRONG here since it survives
business rollback... or is that what you want? argue it). For observability
audits, catch-log-continue so auditing can't cause outages. The interview
point is showing you saw the trade-off.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| proxy mechanics revealed | @Transactional/@Async/@Cacheable/@PreAuthorize rules | `01-foundations/05`, `03-async-and-scheduling/01`, `08-caching`, `06-security` |
| PERFORMANCE_LOGGER / AUDIT_LOGGER | named loggers, additivity | `01-foundations/07` |
| @LogExecutionTime | Micrometer @Timed | `07-observability` |
| never log args by default | secrets/PII log hygiene | `01-foundations/07` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
