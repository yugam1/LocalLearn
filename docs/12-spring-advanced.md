# Advanced Spring — Scopes, Conditionals, Profiles, Versioning
**⬜ Not Started · plan: Phase 10, Tasks 44–47** — Bean lifecycle, @Conditional*, environment config, API evolution

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** This phase is about **which beans exist, how long they live,
and with what config** — the container's decision-making. Three axes:
**scope** (one instance or many, per what), **conditions/profiles** (does the
bean exist in THIS environment), **externalized config** (same jar, different
values everywhere). Plus API versioning: the same discipline applied to your
public contract.

```
scope:      singleton (default, stateless!) · prototype · request · session
existence:  @Profile("prod") · @ConditionalOnProperty/Class/Bean/MissingBean
config:     application.yml  ⊕  application-{profile}.yml (override)  ⊕  ${ENV_VARS}
lifecycle:  new() → inject → @PostConstruct → serve → @PreDestroy
```

**Five rules you must never get wrong:**
1. Singletons are shared by every request thread → they must be **stateless** (mutable fields on a @Service = race condition — [02-concurrency/03](02-concurrency/03-atomicity-races-cas.md)).
2. Injecting shorter-lived into longer-lived needs indirection: request-scoped into singleton = **scoped proxy**; prototype into singleton = `ObjectProvider`/`@Lookup` (plain @Autowired freezes ONE instance forever).
3. `@ConditionalOnProperty` + `@ConditionalOnMissingBean` = the feature-flag/fallback duo — and the backbone of Boot's ENTIRE auto-configuration.
4. Profile files **override** base: application.yml holds the invariants, application-{dev,prod,test}.yml the differences; prod values come from `${ENV_VARS}`.
5. Group config into validated `@ConfigurationProperties` POJOs; `@Value` only for one-offs. Version APIs via URI path; **additive changes don't need a new version — removals/type changes do.**

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> <code>@Scope("prototype") class OrderBuilder</code> is @Autowired into a singleton @Service, which calls <code>orderBuilder.reset()</code> per request "since it's prototype." Two concurrent requests — what actually happens?</summary>

They share ONE OrderBuilder and corrupt each other's state. Prototype means
"new instance per **injection point**" — the singleton was injected exactly
once, at startup; that one prototype instance lives as long as the singleton.
Fix: `ObjectProvider<OrderBuilder>.getObject()` per use, `@Lookup` method
injection, or (request-shaped state) a request-scoped bean with
`proxyMode = TARGET_CLASS`.
</details>

<details>
<summary><b>P2.</b> Deploy with <code>SPRING_PROFILES_ACTIVE=prod</code>, but the DATABASE_URL env var is unset. application-prod.yml says <code>url: ${DATABASE_URL}</code>. When and how does this fail?</summary>

At startup — placeholder resolution fails (or Hikari gets the literal string
"${DATABASE_URL}" and dies connecting). Which is the GOOD failure mode:
fail-fast at boot beats limping into traffic against the wrong DB. Sharpen it:
no default (`${DATABASE_URL:fallback}` would mask the mistake) and — better —
a `@Validated @ConfigurationProperties` class whose @NotBlank turns any missing
config into a named startup error.
</details>

<details>
<summary><b>P3.</b> Mobile app v1 parses order JSON strictly. Team ships: (a) new optional field <code>trackingNumber</code>, (b) renames <code>totalAmount</code> → <code>grandTotal</code>, (c) status enum gains <code>REFUND_PENDING</code>. Which breaks v1 clients, and what does each demand?</summary>

(a) Non-breaking IF clients ignore unknown fields (the tolerant-reader norm) —
ship in place. (b) Breaking — the field clients read vanished → new version
(v2 URI) with a deprecation window for v1. (c) The sneaky one: additive, but
clients switch on the enum — unknown value may crash them. Contractually
breaking unless v1 documented "expect unknown statuses." Rule: additive +
tolerant readers = evolve in place; removals/renames/type changes = version.
</details>

---

## 📖 THE STORY

### 1. Scopes — and the statelessness contract

Singleton (default): one instance serves all threads → the thread-safety
burden is on you, discharged by having **no mutable fields** (final injected
collaborators only). Prototype: new per injection/getBean — Spring stops
managing it after creation (**no @PreDestroy!**). Request/session: web-tied;
inject into singletons via `proxyMode = ScopedProxyMode.TARGET_CLASS` — the
proxy re-resolves the real bean per request (`RequestContext{tenantId,
correlationId}` is the classic — same job MDC does for logs, typed).

Lifecycle hooks: `@PostConstruct` (after injection — cache warmup) and
`@PreDestroy` (before shutdown — flush) beat the interface variants
(InitializingBean/DisposableBean) — annotations don't couple you to Spring.

### 2. Conditional beans — Boot's own trick, in your hands

```java
@Bean @ConditionalOnProperty(name = "feature.kafka.enabled", havingValue = "true")
public KafkaProducerService kafkaProducer() { ... }

@Bean @ConditionalOnMissingBean(KafkaProducerService.class)   // the fallback
public KafkaProducerService noOpProducer() { ... }
```

The pair is the pattern: real bean if flagged on, no-op otherwise — callers
inject the interface and never know. Full menu: `@ConditionalOnClass`
(classpath-driven — how spring-boot-starter-* work: add the jar, beans
appear), `@ConditionalOnBean`, `@Profile("prod")`, and custom `Condition`
implementations (`"AWS".equals(env.getProperty("deployment.environment"))`).
Interview gold: *auto-configuration IS this* — Boot's @AutoConfiguration
classes are stacks of conditionals with @ConditionalOnMissingBean letting your
beans win.

### 3. Profiles + type-safe config

Layering: `application.yml` (base: app name, port, graceful shutdown) +
`application-{profile}.yml` (overrides). The project's set:
- **dev**: localhost DB/Kafka, show-sql, ddl-auto update, DEBUG, actuator `*`.
- **prod**: everything `${ENV}`, show-sql off, **ddl-auto none**, INFO,
  actuator narrowed, acks=all. (Notice how many earlier "key defaults to
  override" land here — this file is where the checklist becomes real.)
- **test**: H2, embedded Kafka, create-drop.

Groups of related config → `@ConfigurationProperties(prefix = "order")`,
`@Validated`, nested POJOs, defaults in-line:

```java
@ConfigurationProperties(prefix = "order") @Validated @Data
public class OrderProperties {
    @NotNull private Integer maxItemsPerOrder = 100;
    @Min(1) @Max(365) private Integer orderRetentionDays = 90;
    private Kafka kafka = new Kafka();   // order.kafka.retry-attempts etc.
}
```

Typed, validated at startup, IDE-completable, injectable as one object —
@Value survives only for single one-off reads.

### 4. API versioning — evolving the contract

| Strategy | Example | Verdict |
|---|---|---|
| **URI path** | `/api/v1/orders` → V1 controller | **Default choice**: visible in logs, gateway-routable, curl-able, cacheable |
| Header | `headers = "API-Version=1"` | clean URIs; invisible, awkward to test |
| Accept/content-negotiation | `produces = "application/vnd.ecommerce.v1+json"` | REST-pure; heavy client ceremony |
| Query param | `params = "version=1"` | caching pollution — avoid |

The versioning decision tree (P3): **additive optional fields** → same
version, rely on tolerant readers. **Removals, renames, type changes,
tightened requirements** → new version, run both, deprecate v1 with a sunset
date. Version DTOs can extend (`OrderResponseV2 extends OrderResponseV1 +
trackingNumber, tags`) while the divergence is additive.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Why must singleton beans be stateless, and what makes them so in practice?</summary>

One instance is called concurrently by every request thread; a mutable field
is a shared write target → lost updates/corruption. In practice: only final
injected collaborators, method-local state, anything per-request passed as
parameters or held in request-scoped beans/MDC.
</details>

<details><summary><b>Q2.</b> Three ways to get a fresh prototype inside a singleton — and what plain @Autowired does wrong.</summary>

ObjectProvider<T>.getObject() per use; @Lookup method injection; (last resort)
ApplicationContext.getBean(). Plain @Autowired resolves ONCE at startup — the
"prototype" becomes a de-facto singleton with shared mutable state.
</details>

<details><summary><b>Q3.</b> How does a request-scoped bean get injected into a singleton without breaking?</summary>

Scoped proxy (proxyMode = TARGET_CLASS): the singleton holds a proxy; each
method call resolves the real instance bound to the current request
(ThreadLocal-backed). Without the proxy, startup fails — no request exists to
create the bean from.
</details>

<details><summary><b>Q4.</b> Explain Boot auto-configuration in terms of this phase's annotations.</summary>

Auto-config classes are @Conditional stacks: @ConditionalOnClass (jar
present?) + @ConditionalOnProperty (enabled?) + @ConditionalOnMissingBean
(user hasn't defined their own?). That last one is why declaring your own
DataSource/CacheManager silently replaces Boot's — user beans win by
conditional design.
</details>

<details><summary><b>Q5.</b> Profile layering and the prod-config golden rules.</summary>

Base application.yml always loads; application-{active-profile}.yml overrides
per key. Prod rules: secrets/URLs from env vars (never in git), ddl-auto none,
show-sql off, actuator exposure narrowed, INFO logging. Test: H2 +
create-drop + embedded Kafka.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> @ConfigurationProperties vs @Value — four concrete advantages.</summary>

Whole-hierarchy binding (nested POJOs); startup validation via @Validated +
Bean Validation; type safety and relaxed binding (max-items-per-order →
maxItemsPerOrder); IDE metadata/completion; injectable+mockable as one
object. @Value = single scattered strings, typo-checked never.
</details>

<details><summary><b>Q7.</b> Which API changes are breaking? Classify: new optional response field, new required request field, removed field, new enum constant.</summary>

New optional response field: safe (tolerant readers). New REQUIRED request
field: breaking — old clients don't send it. Removed/renamed field: breaking.
New enum constant: contract-dependent — breaking for clients that
exhaustively switch; document "expect unknown values" from day one.
</details>

<details><summary><b>Q8.</b> Why does prototype scope skip @PreDestroy, and what's the operational consequence?</summary>

Spring hands the instance over and stops tracking it — no destruction
callback at shutdown. Prototype beans holding closeable resources leak unless
the CALLER closes them. Another reason prototypes are rare; scoped or plain
`new` usually fits better.
</details>

---

## 🃏 FLASHCARDS

```
Singleton contract	Shared by all threads → stateless (final collaborators only)
Prototype trap	@Autowired into singleton = ONE frozen instance; use ObjectProvider/@Lookup
Prototype lifecycle gap	Spring never calls @PreDestroy on prototypes
Request-scoped into singleton	proxyMode = ScopedProxyMode.TARGET_CLASS (per-request resolution)
Lifecycle order	constructor → injection → @PostConstruct → serve → @PreDestroy
Feature-flag bean duo	@ConditionalOnProperty (real) + @ConditionalOnMissingBean (no-op fallback)
Auto-configuration =	@ConditionalOnClass/Property/MissingBean stacks; your beans win
Profile layering	application.yml base ⊕ application-{profile}.yml overrides ⊕ ${ENV}
Config groups	@ConfigurationProperties(prefix) + @Validated POJO; @Value for one-offs
Versioning default	URI path /api/v1/ — visible, routable, cacheable
Needs a new version	Remove/rename/retype fields, new required inputs — NOT additive optionals
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| singleton statelessness | shared mutable state races | `02-concurrency/03` |
| RequestContext scoped bean | MDC per-request context | `01-foundations/07` |
| conditional no-op fallback | resilience fallbacks | `09-resilience` |
| profile config layering | Config Server per-env | `10-microservices` |
| feature flags via properties | runtime flags / canary | `16-production-hardening` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
