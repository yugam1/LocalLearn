# 📚 LocalLearn — Spring Boot Production Learning Plan
## Master Index

---

## 📁 Files in This Directory

### Plan Overview
| File | Contents |
|---|---|
| `plan.md` | Full learning path, progress tracker, all 14 phases overview |

---

### Phase 1: Core Foundation ✅ COMPLETED
| File | Tasks | Topics |
|---|---|---|
| `phase1_task1.md` | Task 1 | Project setup, REST API, DI, IoC, Layered architecture |
| `phase1_task2.md` | Task 2 | Exception handling, @ControllerAdvice, Bean Validation |
| `phase1_task3.md` | Task 3 | JPA entities, relationships, HikariCP, @Transactional basics |
| `phase1_task4.md` | Task 4 | Specifications API, DTO projections, pagination, JOIN FETCH |
| `phase1_task5.md` | Task 5 | Transaction propagation, isolation levels, optimistic/pessimistic locking |
| `phase1_task6.md` | Task 6 | N+1 problem, @BatchSize, fetch types, Hibernate statistics |
| `phase1_task7.md` | Task 7 | SLF4J, MDC, correlation IDs, Logback config, async appenders |

---

### Phase 2: Concurrency, Async & Messaging 🔄 IN PROGRESS
| File | Tasks | Topics |
|---|---|---|
| `phase2_task8.md` | Task 8 | Thread pools, ThreadPoolTaskExecutor, @Async, CompletableFuture |
| `phase2_task9.md` | Task 9 | @Scheduled, cron expressions, fixedRate vs fixedDelay, ShedLock |
| `phase2_task10.md` | Task 10 | Kafka producer/consumer basics, topics, partitions, consumer groups |
| `phase2_task11.md` | Task 11 | Kafka advanced: DLT, idempotency, batch consumer, offset management |

---

### Phase 3–14: Remaining Phases ⬜ NOT STARTED
| File | Phase | Topics |
|---|---|---|
| `phase3_tasks13_to_18.md` | 3 — Testing | Mockito, @WebMvcTest, @DataJpaTest, TestContainers, JaCoCo |
| `phase4_tasks19_to_22.md` | 4 — Security | JWT, Spring Security, RBAC, OAuth2, CORS |
| `phase5_tasks24_to_27.md` | 5 — Observability | Actuator, Micrometer, Prometheus, Zipkin |
| `phase6_tasks28_to_30.md` | 6 — Caching | @Cacheable, Redis, Caffeine, multi-level cache |
| `phase7_tasks31_to_34.md` | 7 — Resilience | Resilience4j, Circuit Breaker, Retry, Rate Limiting, Bulkhead |
| `phase8_tasks35_to_41.md` | 8 — Microservices | Eureka, API Gateway, Config Server, Feign, Saga, Outbox |
| `phase9_tasks42_to_43.md` | 9 — AOP | Aspects, Pointcuts, @Around, custom annotations |
| `phase10_tasks44_to_47.md` | 10 — Advanced Spring | Bean scopes, @Conditional, Profiles, API versioning |
| `phase11_tasks48_to_51.md` | 11 — DB Advanced | Flyway, multi-tenancy, indexing, read replicas |
| `phase12_tasks52_to_53.md` | 12 — API Docs | SpringDoc/Swagger, pagination standards |
| `phase13_tasks54_to_57.md` | 13 — DevOps | Docker multi-stage, Docker Compose, Kubernetes, CI/CD |
| `phase14_tasks58_to_60.md` | 14 — Hardening | Feature flags, Blue-Green, Canary, Graceful shutdown |

---

## 🎯 Interview Priority Order

### Must Know (Do First):
1. ✅ Phase 1 — All 7 tasks (REST, JPA, Transactions, Logging)
2. ✅ Phase 2 — Tasks 8-11 (Thread pools, Scheduling, Kafka)
3. ⬜ Phase 3 — Testing (Mockito, TestContainers)
4. ⬜ Phase 4 — Security + JWT
5. ⬜ Phase 5 — Actuator + Metrics

### Should Know (Do Next):
6. ⬜ Phase 6 — Redis caching
7. ⬜ Phase 7 — Resilience4j
8. ⬜ Phase 8 — Microservices + Spring Cloud

### Good to Know:
9. ⬜ Phase 9 — AOP
10. ⬜ Phase 10 — Advanced Spring
11. ⬜ Phase 11 — DB Advanced
12. ⬜ Phases 12-14 — Docs, DevOps, Hardening

---

## 🔑 Quick Interview Cheat Sheet

### Most Asked Questions:
- **DI/IoC** → phase1_task1.md
- **N+1 problem** → phase1_task6.md
- **@Transactional propagation** → phase1_task5.md
- **MDC/Correlation IDs** → phase1_task7.md
- **Thread pool sizing** → phase2_task8.md
- **Kafka consumer groups** → phase2_task10.md
- **DLT pattern** → phase2_task11.md
- **JWT authentication** → phase4_tasks19_to_22.md
- **Circuit breaker** → phase7_tasks31_to_34.md
- **Blue-Green vs Canary** → phase14_tasks58_to_60.md

### Key Formulas:
- **Thread pool (I/O-bound):** `cores × (1 + wait_time/cpu_time)`
- **Connection pool:** `(cores × 2) + effective_spindle_count`
- **Batch size (Kafka):** `peak_RPS × avg_duration_s × safety_factor`

### Key Defaults to Override:
- `@ManyToOne` → always override to `FetchType.LAZY`
- `open-in-view` → always `false`
- `ddl-auto` → always `none` in prod (use Flyway)
- `enable-auto-commit` (Kafka) → always `false` (manual ack)
- `@Scheduled` pool → always configure custom ThreadPoolTaskScheduler
