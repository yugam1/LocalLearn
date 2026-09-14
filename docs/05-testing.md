# Testing — Mockito, Slices, TestContainers, JaCoCo
**🔄 Implemented · plan: Phase 3, Tasks 13–18** (73 unit/slice tests green; 31 container-backed ITs written, blocked on local Docker)

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Tests form a **pyramid of context sizes**. The less Spring you
load, the faster and more precise the test — so load exactly the slice under
test and fake the rest. Pure Mockito (no context, ms) → `@WebMvcTest` /
`@DataJpaTest` (one slice) → `@SpringBootTest` + TestContainers (everything,
real Postgres/Kafka in Docker). Speed is a feature: fast tests run every build;
slow ones get skipped and rot.

```
            @SpringBootTest + containers   ← few, slow, REAL (mvn verify / Failsafe, *IT)
        @WebMvcTest      @DataJpaTest      ← slice: web only / JPA only
   @ExtendWith(MockitoExtension)           ← many, milliseconds, no Spring (mvn test / Surefire, *Test)
```

**Five rules you must never get wrong:**
1. `@Mock` + `@InjectMocks` = **no Spring at all**; `@MockBean` = a mock **inside** a Spring context (slices). Different worlds, don't mix them up.
2. `@WebMvcTest` loads controllers + advice + validation only — service is `@MockBean`; it tests HTTP semantics (status, JSON shape, validation), not business logic.
3. `@DataJpaTest` + TestContainers + `Replace.NONE` = real PostgreSQL; `entityManager.flush()` + `clear()` before asserting, or you're testing the cache, not the query.
4. Naming split is the build architecture: `*Test` → Surefire (`mvn test`, no infra), `*IT` → Failsafe (`mvn verify`, needs Docker).
5. Coverage gates lie unless scoped: JaCoCo 0.80 **per tested class** (with includes), not bundle-wide where dead config classes dilute the number.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> In a @WebMvcTest for OrderController, the developer asserts that a valid POST returns 201 AND that the order landed in the database. What happens?</summary>

The DB assertion **can't even compile/wire** — there is no database. @WebMvcTest
loads only the web slice; OrderService is a @MockBean returning whatever you
stubbed. The test proves: routing, status code, JSON serialization, validation,
advice mapping. Persistence proof belongs to @DataJpaTest or a full IT. Knowing
*what each slice can prove* is the actual skill.
</details>

<details>
<summary><b>P2.</b> @DataJpaTest: persist an order with 2 items via the repository, then immediately <code>findByIdWithItems(id)</code> and assert 2 items. It passes — but what did it actually prove, and what step is missing?</summary>

Probably nothing about the query: the order is still in the persistence context
(first-level cache), so `findById...` may return the SAME in-memory instance
without meaningfully hitting SQL for the items. Missing:
`entityManager.flush()` then `clear()` — detach everything, force a real DB
round trip. Only then does "items has size 2" prove the JOIN FETCH works.
</details>

<details>
<summary><b>P3.</b> Your repository tests all pass on H2 in-memory. You deploy; a native query with PostgreSQL <code>ON CONFLICT</code> and a JSONB column blow up. Why did tests pass, and what's the structural fix?</summary>

H2 isn't PostgreSQL — it accepts a different SQL dialect, lacks JSONB/ON
CONFLICT semantics (or emulates them differently). Tests validated your code
against a database you don't run. Fix: TestContainers — a real postgres:15
container per test run, `@DynamicPropertySource` wiring its JDBC URL in, and
`Replace.NONE` so Boot doesn't swap H2 back.
</details>

---

## 📖 THE STORY

### 1. Pure unit tests — Mockito, no Spring

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {
    @Mock OrderRepository orderRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @Captor ArgumentCaptor<Order> orderCaptor;
    @InjectMocks OrderServiceImpl orderService;   // real class, fake collaborators
}
```

The vocabulary you use daily:

```java
// stub
when(repo.findByIdWithItems(1L)).thenReturn(Optional.of(order));
doThrow(new RuntimeException("DB down")).when(repo).save(any());
// verify behavior
verify(kafkaProducerService).publishOrderCreated(any(OrderCreatedEvent.class));
verify(repo, never()).delete(any());
// capture what was passed
verify(repo).save(orderCaptor.capture());
assertThat(orderCaptor.getValue().getCustomerEmail()).isEqualTo("john@example.com");
// exceptions
assertThatThrownBy(() -> orderService.getOrderById(999L))
    .isInstanceOf(OrderNotFoundException.class).hasMessageContaining("999");
```

Test names as specs: `createOrder_validRequest_createsOrderAndPublishesEvent` —
method_condition_outcome. Arrange/Act/Assert, one behavior per test. Mock vs
**Spy**: mock = all-fake (default returns); spy = real object with selective
stubs (`doReturn(5).when(spyList).size()`) — for partial mocking, rare by design.

### 2. @WebMvcTest — the HTTP contract slice

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean OrderService orderService;      // mock INSIDE the slim context

    @Test
    void createOrder_blankName_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidReq)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[0].field").value("customerName"));
    }
}
```

This slice is where [task 2](01-foundations/02-exceptions-validation.md)'s whole error contract gets
locked in: every exception→status mapping, every validation message, the
ErrorResponse shape — 21 controller tests + 34 parameterized Bean-Validation
tests (`@ParameterizedTest` over invalid field values) in this project.

### 3. @DataJpaTest — the query slice, on the real database

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)   // don't swap in H2!
@Testcontainers
class OrderRepositoryIT extends AbstractPostgresIT {
    @Autowired OrderRepository orderRepository;
    @Autowired TestEntityManager entityManager;

    @Test
    void findByIdWithItems_loadsItemsInOneQuery() {
        Order order = TestData.orderWithItems(2);
        entityManager.persistAndFlush(order);
        entityManager.clear();                        // detach — force real SQL

        assertThat(orderRepository.findByIdWithItems(order.getId()))
            .hasValueSatisfying(o -> assertThat(o.getItems()).hasSize(2));
    }
}
```

TestContainers wiring: `@Container static PostgreSQLContainer<?> postgres = ...`
+ `@DynamicPropertySource` registering `postgres::getJdbcUrl` etc. **Static** =
one container for the class; the project's `AbstractPostgresIT` base makes it a
singleton across all ITs (containers cost seconds — share them). Each test still
gets a rolled-back transaction, so tests stay independent.

### 4. @SpringBootTest — the whole machine

`webEnvironment = RANDOM_PORT` + `TestRestTemplate`: real HTTP in, real Postgres
+ Kafka containers behind (both wired via `@DynamicPropertySource`). This is
where "201 Created AND row exists AND event published" is a legitimate single
assertion chain. Few of these — they cost seconds each; they prove the wiring,
not every branch (branches were covered cheaply below).

### 5. Build architecture + coverage honesty

- **Surefire** runs `*Test` on `mvn test` — 73 tests, no infrastructure, every
  build, plus the JaCoCo report/gate.
- **Failsafe** runs `*IT` on `mvn verify` — 31 container tests, needs Docker.
  `mvn test` staying Docker-free is what keeps the fast loop alive.
- **JaCoCo**: `prepare-agent` → `report` → `check` with LINE COVEREDRATIO ≥
  0.80 — scoped via `<includes>` to the classes Phase 3 actually tests
  (`OrderServiceImpl` 97%, `GlobalExceptionHandler` 83%, `OrderController` 89%)
  and `<excludes>` for model/dto/config noise. Widen includes as later phases
  add tests — a gate you grow, not a number you game.

<details>
<summary><b>Local Docker blocker (this machine) + fixes to try</b></summary>

Testcontainers (pinned 1.21.3 over the Boot BOM's 1.19.7 — older docker-java
predates Engine 29) can't get a Docker client on Docker Desktop Engine 29.6.2:
every strategy 400s on `/info` while `curl --unix-socket` and `docker info`
succeed — Desktop's socket proxy rejecting docker-java. Tried: DOCKER_HOST both
sockets, DOCKER_API_VERSION=1.43, the upgrade. Next: Desktop → Advanced →
**"Allow the default Docker socket to be used"**; disable Enhanced Container
Isolation; or Colima (`brew install colima && colima start`,
`export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`).
</details>

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> @Mock/@InjectMocks vs @MockBean — context, speed, when each?</summary>

@Mock/@InjectMocks: pure Mockito, zero Spring, milliseconds — service-layer
logic. @MockBean: replaces/adds a bean inside a Spring (slice) context — used
in @WebMvcTest/@SpringBootTest where the context does the injecting. Using
@MockBean where plain Mockito suffices buys you nothing but startup time.
</details>

<details><summary><b>Q2.</b> What exactly does @WebMvcTest load, and what can it therefore prove?</summary>

Controllers, @RestControllerAdvice, converters/validators, MockMvc — no
services, repos, DB, Kafka. Proves HTTP semantics: routing, status codes,
JSON shape, validation errors, exception→status mapping. Business logic proof
lives elsewhere.
</details>

<details><summary><b>Q3.</b> Why TestContainers over H2, and the three wiring pieces?</summary>

H2 validates against a dialect you don't ship — native queries, JSONB, locking
behavior differ; TestContainers runs the real engine in Docker. Wiring:
@Testcontainers + @Container static PostgreSQLContainer;
@DynamicPropertySource registering url/username/password; for @DataJpaTest,
Replace.NONE so Boot keeps your datasource.
</details>

<details><summary><b>Q4.</b> Why flush + clear before the assertion phase of a repository test?</summary>

Otherwise the persistence context serves your own instances back from the
first-level cache — the query (JOIN FETCH, projections, filters) is never
honestly exercised. flush pushes SQL, clear detaches, the repository call then
does a genuine cold read.
</details>

<details><summary><b>Q5.</b> Surefire vs Failsafe — what runs when, and why does the split matter?</summary>

Surefire: `*Test` at `mvn test` — fast, no infra, every build/CI stage.
Failsafe: `*IT` at `mvn verify` — container-backed. The split keeps the
edit-test loop seconds long while still having real-infrastructure proof before
merge; without it, Docker-needing tests contaminate `mvn test` and get skipped.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Mock vs Spy — and why is heavy spy usage a design smell?</summary>

Mock: entirely fake. Spy: real object, selectively stubbed. Needing to spy
your own class usually means it does two things (one you want real, one you
must fake) — a cohesion problem better fixed by extracting the collaborator.
</details>

<details><summary><b>Q7.</b> Why static containers + a shared base class instead of per-test containers?</summary>

Container startup is seconds; per-test = minutes of pure overhead and CI flake
surface. Static = per-class; a singleton base (AbstractPostgresIT) = per-JVM.
Isolation comes from @DataJpaTest's per-test rollback, not from fresh
containers.
</details>

<details><summary><b>Q8.</b> Your JaCoCo bundle-wide 0.80 gate passes while OrderServiceImpl sits at 40%. How?</summary>

Coverage averages: piles of trivially-covered or excluded-adjacent code
(getters, config, mappers) drown the one class that matters. Hence per-class
rules scoped by includes to classes under test, excludes for model/dto/config —
the gate should measure intent, not volume.
</details>

<details><summary><b>Q9.</b> What belongs in an ArgumentCaptor assertion vs a verify() call?</summary>

verify() proves an interaction happened (times, never, order). Captor proves
the CONTENT passed was right — the built entity's fields, the event payload.
If you only verify(any()), a bug that sends the wrong data passes; captors are
how unit tests catch mapping errors.
</details>

---

## 🃏 FLASHCARDS

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

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| constructor injection = mockability | why DI exists | `01-foundations/01` |
| @WebMvcTest locks the error contract | GlobalExceptionHandler mapping | `01-foundations/02` |
| flush/clear vs L1 cache | persistence context mechanics | `01-foundations/03` |
| repository ITs prove JOIN FETCH | N+1 fixes need proof | `01-foundations/06` |
| containers in CI | Docker Compose / pipelines | `15-devops-docker-k8s` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
