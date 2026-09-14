# 🎯 Production-Grade Spring Boot Learning Plan
**Yugam Prasad (Master Y) — Java Spring Boot Interview Prep**
**Target: 6+ Years Experience Role | MAANG-Level Preparation**

> Project: **Multi-Tenant E-Commerce Platform with Microservices**
> Total Estimated Time: **~35–40 hours**

---

## ✅ PROGRESS LEGEND
- ✅ Completed
- 🔄 In Progress
- ⬜ Not Started

---

## ✅ PHASE 1: Core Foundation ⭐⭐⭐

> **Goal:** Solid REST API, JPA, and transaction handling

| # | Task | Time | Status |
|---|------|------|--------|
| 1 | Project Setup + REST API Basics | 1hr | ✅ |
| 2 | Exception Handling + Validation (@Valid, custom validators) | 1hr | ✅ |
| 3 | Database Integration + JPA Entities + Relationships | 1.5hr | ✅ |
| 4 | Repository Layer + Custom Queries + Specifications | 1hr | ✅ |
| 5 | Transaction Management (Propagation, Isolation Levels) | 1.5hr | ✅ |
| 6 | JPA Performance: N+1 Queries, Lazy vs Eager Loading | 1hr | ✅ |
| 7 | Connection Pooling: HikariCP Configuration & Tuning | 1hr | ✅ (covered in Task 6) |

**Phase Total: ~8 hours | What You Built:** Order Management API with proper DB design, N+1 prevention, HikariCP tuning.

---

## ✅ PHASE 2: Concurrency, Async & Messaging ⭐⭐⭐

> **Goal:** Thread pools, async processing, scheduling, Kafka pub/sub

| # | Task | Time | Status |
|---|------|------|--------|
| 8 | Thread Pools & ExecutorService Configuration | 1.5hr | ✅ |
| 9 | Scheduled Tasks (@Scheduled, Quartz Integration) | 1hr | ✅ |
| 10 | Apache Kafka Basics: Producer & Consumer | 1.5hr | ✅ |
| 11 | Kafka Advanced: Consumer Groups, Partitions, Offsets, DLT, Idempotency | 1.5hr | ✅ |
| 12 | Kafka Patterns: Event Sourcing, CQRS, Saga, Outbox | 1hr | ⬜ |
| 13 | Spring Events (Local) + Kafka (Distributed) Integration | 1hr | ⬜ |
| 14 | Reactive Programming with Spring WebFlux (Optional) | 2hr | ⬜ |
| 15 | Performance Optimization & Profiling | 1hr | ⬜ |

**Phase Total: ~10 hours | What You'll Build:** Async order processing, worker pools, Kafka pub/sub event pipeline, scheduled inventory sync.

### Key Concepts Covered So Far (Tasks 8–11):
- ThreadPoolTaskExecutor: corePoolSize, maxPoolSize, queueCapacity, rejection policies
- @Async with MDC propagation via TaskDecorator
- CompletableFuture: allOf, anyOf, exceptionally, orTimeout
- @Scheduled: fixedRate, fixedDelay, cron expressions
- Kafka: Topics, Partitions, Consumer Groups, Offsets
- Kafka: Manual ack, idempotent consumers, DLT, @RetryableTopic
- Kafka: Custom partitioner, batch consumer, pause/resume
- MDC correlation ID propagation through Kafka consumers

---

## 🧵 PHASE 2B: Java Multithreading Foundations ⭐⭐⭐

> **Goal:** The layer *underneath* Spring's async abstraction — what a thread
> is, what the JVM guarantees about memory, and why the bugs happen.
> **Module:** `concurrency-lab/` (standalone, Java 21). **Docs:** `docs/02-concurrency/`

Modules are grouped by **problem schema, not by API** — you index concurrency
knowledge by "what problem does this solve", and grouping by class name produces
recognition without recall. Each block's artifact feeds the next, so revision
happens through *use*: topic 5's queue becomes topic 8's thread pool, which
becomes topic 10's incident.

### Block A — Foundations: what a thread is and why the bugs happen ✅

| # | Topic | Demos | Exercises | Time | Status |
|---|------|-------|-----------|------|--------|
| 1 | Threads, lifecycle, interruption & cancellation | D1–D3 | Ex4 | 1.5hr | ✅ |
| 2 | Java Memory Model: visibility, reordering, safe publication | D4–D6 | Ex2 | 2hr | ✅ |
| 3 | Races, atomicity, CAS, ABA, LongAdder | D7–D9 | Ex1, Ex3 | 2hr | ✅ |
| 4 | Locks, deadlock, ReadWriteLock, Condition | D10–D12 | Ex5, Ex6, Ex7 | 2.5hr | ✅ |

### Block B — Moving work between threads

| # | Topic | Demos | Exercises | Time | Status |
|---|------|-------|-----------|------|--------|
| 5 | Hand-off: blocking queues, backpressure, draining shutdown | D13–D15 | Ex8 | 2hr | ✅ |
| 6 | Shared structures: ConcurrentHashMap, CopyOnWrite, ThreadLocal | — | — | 2hr | ⬜ |
| 7 | Coordination: latch · barrier · semaphore · phaser (as a 2×2) | — | — | 1.5hr | ⬜ |

### Block C — Who runs the work

| # | Topic | Demos | Exercises | Time | Status |
|---|------|-------|-----------|------|--------|
| 8 | ThreadPoolExecutor internals: core → **queue** → max → reject | — | — | 2hr | ⬜ |
| 9 | ForkJoin, parallel streams, virtual threads, structured concurrency | — | — | 2.5hr | ⬜ |

### Block D — Capstone

| # | Topic | Demos | Exercises | Time | Status |
|---|------|-------|-----------|------|--------|
| 10 | **The incident** — diagnose a planted deadlock + pool exhaustion + ThreadLocal leak from thread dumps alone (jstack, jcmd, JFR) | — | — | 1.5hr | ⬜ |

Topic 10 is a simulation, not a topic. You get a broken service and the tools,
with no label saying which mechanism applies — which is the step interviews and
production actually test, and the one a topic-by-topic curriculum never trains.

**Built so far:** 15 runnable demos that visibly misbehave (a loop that never
exits, 92% memory-reordering anomalies, a self-inflicted deadlock the JVM
detects, a `HashMap` that spins forever, a queue that eats 140 MB in 0.4s) +
8 broken exercises with contract tests that fail until fixed. 19 reference
tests all green.

```bash
cd concurrency-lab && ./mvnw test -Dtest=ExerciseTests
```

---

## 🔄 PHASE 3: Testing ⭐⭐⭐

> **Goal:** Complete test suite with 80%+ coverage

| # | Task | Time | Status |
|---|------|------|--------|
| 16 | Unit Testing: Service Layer with Mockito | 1hr | ✅ `OrderServiceImplTest` (18 tests) |
| 17 | Integration Testing: @SpringBootTest, TestRestTemplate | 1hr | 🔄 `OrderApiIT` written (14 tests), never executed — Docker blocked |
| 18 | Repository Testing: @DataJpaTest, TestContainers | 1hr | 🔄 `OrderRepositoryIT` written (17 tests), never executed — Docker blocked |
| 19 | Controller Testing: @WebMvcTest, MockMvc | 1hr | ✅ `OrderControllerTest` (21 tests) |
| 20 | Contract Testing: Spring Cloud Contract | 1hr | ⬜ Not covered by the phase doc either |
| 21 | Advanced Testing: Parameterized, Test Slices, Coverage (JaCoCo) | 1hr | ✅ `OrderRequestValidationTest` (34 tests) + JaCoCo 0.80 gate |

**Phase Total: ~6 hours | What You Built:** 73 passing unit/slice tests on `mvn test`,
31 more container-backed tests wired to `mvn verify`, JaCoCo enforcing 0.80 line coverage
on the classes under test. See `05-testing.md` for the file map and the local
Docker blocker.

---

## ⬜ PHASE 4: Security ⭐⭐⭐

> **Goal:** Enterprise-grade security with JWT and OAuth2

| # | Task | Time | Status |
|---|------|------|--------|
| 22 | Spring Security Setup + JWT Authentication | 1.5hr | ⬜ |
| 23 | Role-Based Access Control (RBAC) + Method Security (@PreAuthorize) | 1hr | ⬜ |
| 24 | OAuth2 & OpenID Connect Integration | 1hr | ⬜ |
| 25 | Security Best Practices: CSRF, CORS, Password Encoding, HTTPS | 0.5hr | ⬜ |

**Phase Total: ~4 hours | What You'll Build:** Secure API with JWT, role-based permissions, OAuth2 login.

---

## ⬜ PHASE 5: Production Monitoring & Observability ⭐⭐⭐

> **Goal:** Full observability stack

| # | Task | Time | Status |
|---|------|------|--------|
| 23 | Structured Logging: SLF4J + Logback + MDC | 1hr | ✅ (Task 7 in original) |
| 24 | Spring Boot Actuator + Custom Health Checks | 1hr | ⬜ |
| 25 | Metrics with Micrometer (Prometheus + Grafana) | 1.5hr | ⬜ |
| 26 | Distributed Tracing: Zipkin / Jaeger Integration | 1hr | ⬜ |
| 27 | APM Integration: New Relic / Datadog | 0.5hr | ⬜ |

**Phase Total: ~5 hours | What You'll Build:** Observable app with correlation IDs, metrics dashboards, distributed tracing.

> Note: Logging (MDC, correlation IDs, SLF4J, Logback, async appenders, JSON/Logstash) was completed as Task 7.

---

## ⬜ PHASE 6: Caching & Performance ⭐⭐

> **Goal:** Hybrid caching strategy

| # | Task | Time | Status |
|---|------|------|--------|
| 28 | Spring Cache Abstraction (@Cacheable, @CacheEvict, @CachePut) | 1hr | ⬜ |
| 29 | Redis Integration: Distributed Caching | 1hr | ⬜ |
| 30 | Caffeine: Local Caching + Multi-Level Cache Strategy | 1hr | ⬜ |

**Phase Total: ~3 hours | What You'll Build:** Cache-aside pattern, Redis + Caffeine hybrid, TTL strategies.

---

## ⬜ PHASE 7: Resilience & Fault Tolerance ⭐⭐⭐

> **Goal:** Production resilience patterns

| # | Task | Time | Status |
|---|------|------|--------|
| 31 | Circuit Breakers with Resilience4j | 1hr | ⬜ |
| 32 | Retry Mechanisms + Fallback Strategies | 1hr | ⬜ |
| 33 | Rate Limiting & Bulkhead Pattern | 1hr | ⬜ |
| 34 | Timeouts & Graceful Degradation | 1hr | ⬜ |

**Phase Total: ~4 hours | What You'll Build:** Resilient external API calls with circuit breakers, retries, rate limiting.

---

## ⬜ PHASE 8: Microservices Architecture ⭐⭐⭐

> **Goal:** Full Spring Cloud ecosystem

| # | Task | Time | Status |
|---|------|------|--------|
| 35 | Service Discovery: Eureka Server & Client | 1.5hr | ⬜ |
| 36 | API Gateway: Spring Cloud Gateway (routing, filters, rate limiting) | 1.5hr | ⬜ |
| 37 | Centralized Configuration: Spring Cloud Config Server | 1hr | ⬜ |
| 38 | Load Balancing: Spring Cloud LoadBalancer | 1hr | ⬜ |
| 39 | Inter-Service Communication: Feign Client | 1hr | ⬜ |
| 40 | Distributed Configuration Management | 1hr | ⬜ |
| 41 | Service Mesh Basics: Istio Overview | 1hr | ⬜ |

**Phase Total: ~8 hours | What You'll Build:** Multi-service architecture with gateway, service discovery, config server.

---

## ⬜ PHASE 9: AOP & Cross-Cutting Concerns ⭐⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 42 | AOP Fundamentals: Aspects, Pointcuts, Advice (logging, perf, security) | 1hr | ⬜ |
| 43 | Custom Annotations + AOP (@LogExecutionTime, @Auditable) | 1hr | ⬜ |

> Note: PerformanceLoggingAspect was introduced in Task 7 as a practical preview.

**Phase Total: ~2 hours | What You'll Build:** Cross-cutting audit logging, performance tracking via AOP.

---

## ⬜ PHASE 10: Advanced Spring Features ⭐⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 44 | Bean Lifecycle & Scopes (Singleton, Prototype, Request, Session) | 1hr | ⬜ |
| 45 | Conditional Beans & Auto-Configuration | 1hr | ⬜ |
| 46 | Profiles & Environment-Specific Configuration | 1hr | ⬜ |
| 47 | API Versioning Strategies (URI, Header, Content Negotiation) | 1hr | ⬜ |

**Phase Total: ~4 hours | What You'll Build:** Multi-environment setup, custom auto-configuration, versioned APIs.

---

## ⬜ PHASE 11: Database Advanced Topics ⭐⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 48 | Database Migration: Flyway | 1hr | ⬜ |
| 49 | Multi-Tenancy Implementation | 1hr | ⬜ |
| 50 | Database Indexing & Query Optimization | 1hr | ⬜ |
| 51 | Read Replicas & Write-Through Caching | 1hr | ⬜ |

**Phase Total: ~4 hours | What You'll Build:** Multi-tenant app with separate schemas, optimized queries.

---

## ⬜ PHASE 12: Documentation & API Management ⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 52 | OpenAPI / Swagger: SpringDoc Integration | 1hr | ⬜ |
| 53 | Pagination, Filtering, Sorting (Spring Data) | 1hr | ⬜ |

> Note: Pagination + Specifications were introduced in Task 4.

**Phase Total: ~2 hours | What You'll Build:** Self-documenting API with Swagger UI.

---

## ⬜ PHASE 13: DevOps & Deployment ⭐⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 54 | Docker: Multi-Stage Builds | 1hr | ⬜ |
| 55 | Docker Compose: Multi-Container Setup | 1hr | ⬜ |
| 56 | Kubernetes Basics: Deployments, Services, ConfigMaps, Secrets | 1.5hr | ⬜ |
| 57 | CI/CD Pipeline: GitHub Actions / Jenkins | 0.5hr | ⬜ |

**Phase Total: ~4 hours | What You'll Build:** Containerized app with K8s deployment manifests.

---

## ⬜ PHASE 14: Production Hardening ⭐⭐

| # | Task | Time | Status |
|---|------|------|--------|
| 58 | Feature Flags Implementation | 1hr | ⬜ |
| 59 | Blue-Green & Canary Deployments | 1hr | ⬜ |
| 60 | Graceful Shutdown & Zero-Downtime Deployments | 1hr | ⬜ |

**Phase Total: ~3 hours | What You'll Build:** Production-ready deployment strategy with feature toggles.

---

## 📊 OVERALL PROGRESS

| Phase | Tasks | Hours | Status |
|-------|-------|-------|--------|
| 1. Core Foundation | 7 | 8hr | ✅ Complete |
| 2. Concurrency + Messaging | 8 | 10hr | 🔄 4/8 Done |
| 3. Testing | 6 | 6hr | 🔄 5/6 Done (2 need Docker to verify) |
| 4. Security | 4 | 4hr | ⬜ |
| 5. Observability | 5 | 5hr | ⬜ Logging ✅ |
| 6. Caching | 3 | 3hr | ⬜ |
| 7. Resilience | 4 | 4hr | ⬜ |
| 8. Microservices | 7 | 8hr | ⬜ |
| 9. AOP | 2 | 2hr | ⬜ |
| 10. Advanced Spring | 4 | 4hr | ⬜ |
| 11. DB Advanced | 4 | 4hr | ⬜ |
| 12. Docs & API | 2 | 2hr | ⬜ |
| 13. DevOps | 4 | 4hr | ⬜ |
| 14. Production Hardening | 3 | 3hr | ⬜ |
| **TOTAL** | **57** | **~67hr** | **~20% Done** |

---

## 🎯 INTERVIEW READINESS

### ✅ Can Answer Confidently NOW:
- Dependency Injection & IoC (constructor injection best practice)
- @Transactional propagation (REQUIRED, REQUIRES_NEW, NESTED)
- Isolation levels (READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE)
- N+1 problem, JOIN FETCH, @BatchSize, DTO projections
- Optimistic (@Version) vs Pessimistic locking (@Lock)
- Thread pool sizing (CPU-bound vs I/O-bound formulas)
- @Async with CompletableFuture (allOf, anyOf, exceptionally)
- MDC and correlation ID propagation (including async threads)
- Kafka: topics, partitions, consumer groups, offsets
- Kafka: manual ack, idempotent consumer, DLT, @RetryableTopic
- Kafka: custom partitioner, batch consumer, consumer lag
- Cron expressions and fixedRate vs fixedDelay scheduling

### 🔄 Need to Cover for Interviews:
- Spring Security + JWT flow
- Testing (@SpringBootTest, @WebMvcTest, TestContainers)
- Circuit breaker pattern (Resilience4j)
- Caching strategies (Redis, Caffeine)
- Microservices patterns (gateway, service discovery)
- API Gateway (routing, rate limiting)

### 📌 Priority Order for Remaining Work:
1. **Testing** (Phase 3) — heavily asked in interviews
2. **Security + JWT** (Phase 4) — must-know for 6+ yr role
3. **Actuator + Metrics** (Phase 5) — production must-have
4. **Caching** (Phase 6) — performance interviews
5. **Resilience4j** (Phase 7) — fault tolerance design
6. **Microservices** (Phase 8) — architecture discussions
7. Remaining phases at your pace

---

## 🏗️ PROJECT STRUCTURE BUILT

```
order-service/
├── src/main/java/com/ecommerce/orderservice/
│   ├── aspect/             # PerformanceLoggingAspect
│   ├── config/             # Async, Kafka, Scheduling configs
│   │   └── decorator/      # MDCTaskDecorator
│   ├── controller/         # REST + Admin + Test controllers
│   ├── dto/
│   │   ├── request/        # OrderRequest, OrderItemRequest
│   │   ├── response/       # OrderResponse, OrderItemResponse
│   │   ├── error/          # ErrorResponse, ValidationError
│   │   ├── projection/     # OrderSummary, OrderWithItemCount
│   │   └── OrderSummaryDTO
│   ├── event/              # BaseEvent, OrderCreatedEvent, etc.
│   ├── exception/          # Custom exceptions + GlobalExceptionHandler
│   ├── filter/             # MDCFilter
│   ├── kafka/
│   │   └── partitioner/    # OrderPartitioner
│   ├── model/              # JPA entities (Order, OrderItem, Product, etc.)
│   ├── repository/         # JPA repositories
│   ├── service/            # Service interfaces + implementations
│   │   └── impl/
│   ├── specification/      # OrderSpecification (JPA Criteria)
│   ├── util/               # LoggingUtils
│   └── validation/         # Custom validators
├── src/main/resources/
│   ├── application.yml
│   └── logback-spring.xml
├── docker-compose-kafka.yml
└── docs/
    └── plan.md             ← YOU ARE HERE
```

---

## 🔗 KEY TECHNOLOGIES USED

| Technology | Version | Purpose |
|------------|---------|---------|
| Spring Boot | 3.2.x | Core framework |
| Java | 17 / 21 | Language |
| PostgreSQL | 15 | Primary database |
| Apache Kafka | 7.5.0 (Confluent) | Event streaming |
| HikariCP | Built-in | Connection pooling |
| Hibernate | Built-in | ORM |
| Logback | Built-in | Logging |
| Logstash Encoder | 7.4 | JSON logging |
| Lombok | Latest | Boilerplate reduction |
| Docker | Latest | Containerization |
| Kafka UI | Latest | Kafka monitoring |

---

## 📚 TASK DETAIL REFERENCE

Every task/phase doc in `docs/` now follows the **5-zone learning format**
(see `docs/02-concurrency/README.md` for the method): ⚡ Core Card (60-second essence) →
🔮 Predict First (answer before reading) → 📖 Story → 🎯 Retrieval Gym
(hidden answers, self-test) → 🃏 Flashcards + 🗓 spaced Revision Log.

Revision aids live in `docs/revision/`:
- `cheatsheet.md` — all Core Cards on one page (~25 min full-curriculum skim)
- `flashcards.md` — master deck, Anki-importable
- `mock_interview.md` — 25 interleaved cross-phase questions, scored

---

*Last updated: Task 11 (Kafka Advanced) completed.*
*Next: Task 12 — Kafka Patterns (Event Sourcing, CQRS, Saga, Outbox)*
