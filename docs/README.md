# 📚 LocalLearn — Spring Boot Production Learning System
## Master Index

Every doc in this directory follows one learning format, engineered for
retention, not just reference. Read this page once; it tells you how to learn
from, and revise with, everything else.

---

## 🧠 How every doc works (the 5 zones)

| Zone | Job | When you use it |
|---|---|---|
| **⚡ Core Card** | the 20% that carries 80% — mental model, one diagram, 5 rules | every revision pass, 60 seconds |
| **🔮 Predict First** | puzzles you answer BEFORE reading (answers hidden) | first learn only — being wrong here is the point |
| **📖 The Story** | the teaching narrative, failure-first | first learn + when a gym question stumps you |
| **🎯 Retrieval Gym** | questions with hidden answers, Tier 1 (must-know) / Tier 2 (depth) | every revision pass — answer ALOUD, then open |
| **🗓 Revision Log** | spaced-repetition checkboxes: R1 +1d · R2 +3d · R3 +1w · R4 +3w | tick after each pass |

Plus **🃏 Flashcards** (Anki-importable) and **🔗 Same Idea, Different Layer**
(cross-links — one mental model recurring across layers is the retention
engine: `@Version` ↔ CAS, bulkhead ↔ thread pools, isolation ↔ JMM).

### The workflow

- **First learn:** Predict First (commit to answers!) → Story → Gym same day → tick nothing yet.
- **Revision pass (~6 min/topic):** Core Card + Gym only. Miss a question → reread only that Story section. Miss ≥2 Tier-1 → reset that topic to R1.
- **Quick skim (pre-interview):** [`revision/cheatsheet.md`](revision/cheatsheet.md) — every Core Card on one page (~25 min for the whole curriculum).
- **Interleaved test (every few weeks):** [`revision/mock_interview.md`](revision/mock_interview.md) — 25 shuffled cross-phase questions, scored.
- **Micro-drills:** [`revision/flashcards.md`](revision/flashcards.md) — all decks, tab-separated for Anki import.

---

## 📁 Curriculum map

Docs are organized by **topic, in learning order** — the numbered folders/files
below ARE the path. (`plan.md` keeps the original phase/task numbering for
progress tracking; the mapping is noted per topic.)

### Plan & revision
| File | Contents |
|---|---|
| `plan.md` | Full learning path, progress tracker, all 14 phases |
| `revision/cheatsheet.md` | **The one-page brain** — all Core Cards |
| `revision/flashcards.md` | Master flashcard deck (Anki-ready) |
| `revision/mock_interview.md` | 25-question interleaved mock, with scoring |

### 01 · Foundations ✅ *(plan: Phase 1)*
| File | Topics |
|---|---|
| [`01-di-ioc-rest-layers`](01-foundations/01-di-ioc-rest-layers.md) | DI/IoC, constructor injection, REST design, DTOs, layers |
| [`02-exceptions-validation`](01-foundations/02-exceptions-validation.md) | @RestControllerAdvice, exception hierarchy, Bean Validation |
| [`03-jpa-entities-relationships`](01-foundations/03-jpa-entities-relationships.md) | JPA entities, owning side, cascade/orphanRemoval, HikariCP |
| [`04-repositories-queries-pagination`](01-foundations/04-repositories-queries-pagination.md) | Specifications, Page vs Slice, projections, paginated JOIN FETCH |
| [`05-transactions-isolation-locking`](01-foundations/05-transactions-isolation-locking.md) | Propagation, isolation ladder, optimistic/pessimistic locking |
| [`06-n-plus-one-hikaricp-tuning`](01-foundations/06-n-plus-one-hikaricp-tuning.md) | N+1 both directions, @BatchSize, Hibernate statistics, pool tuning |
| [`07-logging-mdc-correlation-ids`](01-foundations/07-logging-mdc-correlation-ids.md) | Log levels, MDC, correlation IDs, Logback, async appenders |

### 02 · 🧵 Concurrency foundations ✅ *(multithreading lab)*
| File | The one thing |
|---|---|
| [`README`](02-concurrency/README.md) | Index + how to run the demos/exercises |
| [`01-threads-lifecycle-interruption`](02-concurrency/01-threads-lifecycle-interruption.md) | Interruption is a **request**, not a kill |
| [`02-jmm-visibility-happens-before`](02-concurrency/02-jmm-visibility-happens-before.md) | No happens-before edge → guaranteed **nothing** |
| [`03-atomicity-races-cas`](02-concurrency/03-atomicity-races-cas.md) | `volatile` ≠ atomic; the gap in compound actions |
| [`04-locks-deadlock-conditions`](02-concurrency/04-locks-deadlock-conditions.md) | Deadlock = two locks in **two orders** |

Runnable code: **`concurrency-lab/`** (standalone, Java 21) — 12 demos that
visibly misbehave + 7 broken exercises with contract tests.

```bash
cd concurrency-lab
java -cp target/classes com.locallearn.concurrency.t03atomicity.D7_LostUpdates
./mvnw test -Dtest=SolutionTests    # reference — all pass
./mvnw test -Dtest=ExerciseTests    # yours — fail until fixed
```

### 03 · Async & scheduling in Spring ✅ *(plan: Phase 2, tasks 8–9)*
| File | Topics |
|---|---|
| [`01-thread-pools-completablefuture`](03-async-and-scheduling/01-thread-pools-completablefuture.md) | Pool lifecycle (queue-before-grow!), sizing, rejection, CompletableFuture |
| [`02-scheduling-shedlock`](03-async-and-scheduling/02-scheduling-shedlock.md) | fixedRate vs fixedDelay, cron, scheduler pool, ShedLock |

### 04 · Kafka ✅ *(plan: Phase 2, tasks 10–11)*
| File | Topics |
|---|---|
| [`01-fundamentals-partitions-groups`](04-kafka/01-fundamentals-partitions-groups.md) | Log-not-queue, partitions/keys/groups, acks, manual commit |
| [`02-reliability-dlt-idempotency`](04-kafka/02-reliability-dlt-idempotency.md) | Rebalancing, DLT/@RetryableTopic, idempotency ledger, lag, replay |

### 05 · Testing 🔄 *(plan: Phase 3)*
| File | Topics |
|---|---|
| [`05-testing.md`](05-testing.md) | Mockito, @WebMvcTest, @DataJpaTest + TestContainers, Surefire/Failsafe split, JaCoCo |

`mvn test` → 73 unit/slice tests. `mvn verify` → +31 container tests (Docker).

### 06–16 · Remaining topics ⬜ *(plan: Phases 4–14; study-ready docs, same format)*
| File | Topics |
|---|---|
| [`06-security.md`](06-security.md) | JWT flow, filter chain, RBAC, OAuth2, CORS/CSRF |
| [`07-observability.md`](07-observability.md) | Actuator, Micrometer, Prometheus, tracing |
| [`08-caching.md`](08-caching.md) | @Cacheable, Redis, Caffeine, stampede/penetration/avalanche |
| [`09-resilience.md`](09-resilience.md) | Breaker states, retry+jitter, bulkhead, fallbacks |
| [`10-microservices.md`](10-microservices.md) | Eureka, Gateway, Config, Feign, Saga, Outbox |
| [`11-aop-proxies.md`](11-aop-proxies.md) | The proxy machinery revealed, custom annotations |
| [`12-spring-advanced.md`](12-spring-advanced.md) | Scopes, conditionals, profiles, versioning |
| [`13-database-advanced.md`](13-database-advanced.md) | Flyway, multi-tenancy, indexing, replicas |
| [`14-api-documentation.md`](14-api-documentation.md) | SpringDoc, pagination contracts |
| [`15-devops-docker-k8s.md`](15-devops-docker-k8s.md) | Docker multi-stage, Compose, K8s, CI/CD |
| [`16-production-hardening.md`](16-production-hardening.md) | Feature flags, blue-green/canary, graceful shutdown |

---

## 🎯 Interview priority order

**Must know:** 1️⃣ Foundations (01) · 2️⃣ Concurrency + async (02–03) · 3️⃣ Kafka (04) · 4️⃣ Testing (05) · 5️⃣ Security (06) · 6️⃣ Observability (07)
**Should know:** Caching (08) · Resilience (09) · Microservices (10)
**Good to know:** AOP (11) · Spring advanced (12) · Topics 13–16

### Most-asked question → doc
DI/IoC → `01-foundations/01` · N+1 → `01-foundations/06` · propagation → `01-foundations/05` ·
MDC → `01-foundations/07` · pool sizing → `03-async-and-scheduling/01` · volatile vs atomic →
`02-concurrency/03` · happens-before → `02-concurrency/02` · deadlock →
`02-concurrency/04` · consumer groups → `04-kafka/01` · DLT → `04-kafka/02` ·
JWT → `06-security` · circuit breaker → `09-resilience` · saga/outbox → `10-microservices` ·
blue-green vs canary → `16-production-hardening`

---

## 🔑 Numbers & defaults to know cold

**Formulas**
- Thread pool (I/O-bound): `cores × (1 + wait/cpu)` · (CPU-bound): `cores + 1`
- Connection pool: `(cores × 2) + spindles`
- Queue size: `peak_RPS × avg_duration_s × safety`

**Defaults to override, always**
- `@ManyToOne`/`@OneToOne` → `FetchType.LAZY`
- `open-in-view` → `false`
- `ddl-auto` → `none` in prod (Flyway)
- Kafka `enable-auto-commit` → `false` (manual ack)
- `@Scheduled` → custom `ThreadPoolTaskScheduler` (default = 1 thread!)
- `@Enumerated` → `STRING` (never ORDINAL)

**Rules of thumb**
- `volatile` = visibility + ordering. **Never** atomicity of `x++`
- Deadlock is never "two locks" — it's **two locks in two different orders**
- Every `catch (InterruptedException)` → rethrow **or** restore the flag
- Always `while (!condition) cond.await()` — never `if`
- The proxy trap is ONE trap: @Transactional, @Async, @Cacheable, @PreAuthorize — `this.method()` bypasses them all
- At-least-once is everywhere → idempotency (eventId ledger, unique index) is not optional
