# Production Hardening — Flags, Blue-Green/Canary, Graceful Shutdown
**⬜ Not Started · plan: Phase 14, Tasks 58–60** — Deploy≠release, traffic-switch strategies, clean death, expand-contract migrations

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Hardening = making change boring. Three separations do it:
**deploy ≠ release** (feature flags: code ships dark, a toggle releases it),
**new version ≠ new traffic** (blue-green switches atomically, canary ramps a
percentage — both make rollback a routing change, not a redeploy), and
**process exit ≠ dropped work** (graceful shutdown drains everything before
dying). Underneath all three: **expand-contract migrations**, because during
any rollout old and new code run against ONE schema simultaneously.

```
flags:      if (flags.isEnabled("new-checkout")) newPath else oldPath   ← rollback = toggle
blue-green: Service selector slot:blue → slot:green (atomic, instant back)
canary:     Ingress canary-weight: 10 → 25 → 50 → 100 (watch metrics between steps)
shutdown:   SIGTERM → stop intake → drain HTTP → commit Kafka offsets → close pool → exit
```

**Five rules you must never get wrong:**
1. Feature flags decouple deploy from release: dark launches, per-user betas, instant kill switches — and they enable trunk-based development.
2. Blue-green = two full environments, one selector flip — instant rollback, double cost. Canary = weighted ramp — small blast radius, needs traffic-splitting infra + metrics discipline.
3. During ANY rollout, **old code and new schema coexist** → migrations must be backward compatible: add nullable/defaulted, never drop/rename/retype in the same release.
4. Rename-a-column = the 3-deploy dance: add new column → write both/backfill → read new → (later) drop old.
5. `terminationGracePeriodSeconds` (K8s) > `timeout-per-shutdown-phase` (Spring) — SIGKILL before the drain finishes = dropped requests, uncommitted offsets, leaked connections.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> V10 migration renames <code>total_amount</code> → <code>grand_total</code>, deployed with the new code as one release, rolling update, 3 pods. What happens DURING the rollout (pods 1 new, 2 old)?</summary>

The migration runs first (app startup), so the column is renamed while two
old pods still serve traffic — every query in old code referencing
total_amount now throws SQL errors: **the rollout itself causes the outage**,
and rolling back the code doesn't help because the schema moved. This is why
rename is forbidden and the expand-contract dance exists: schema change and
code change must each be individually safe alongside the other version.
</details>

<details>
<summary><b>P2.</b> Blue is live (v1). You deploy green (v2), patch the Service selector to green, and 10 minutes later discover v2 wrote malformed rows to the shared database. You flip the selector back to blue. What's fixed and what isn't?</summary>

Traffic is fixed instantly — blue serves again. The DATA isn't: 10 minutes of
malformed rows persist, and v1 may now choke on them. Blue-green's instant
rollback covers stateless behavior only; state is shared across slots. That's
why schema/data changes need expand-contract independently of the traffic
strategy, and why canary (1% writing bad rows, caught by metrics) limits
data blast radius in a way blue-green doesn't.
</details>

<details>
<summary><b>P3.</b> Kafka consumer pod gets SIGTERM mid-batch. Compare what happens with graceful shutdown configured vs a plain kill, for: in-flight HTTP requests, the half-processed batch, DB connections.</summary>

Graceful: HTTP intake stops but in-flight requests complete (≤30s); the
consumer finishes/stops polling, **commits its offsets**, leaves the group
cleanly (fast, announced rebalance); Hikari drains and closes. Kill: clients
get connection resets; offsets uncommitted → the whole batch redelivered to
another consumer (duplicates — your idempotency ledger absorbs it, but why
create the work); the group waits out the session timeout before rebalancing
(seconds of frozen partitions); TCP connections die dirty. Graceful shutdown
is what makes deploys invisible.
</details>

---

## 📖 THE STORY

### 1. Feature flags — three maturity tiers

1. **Property-based** — `@ConfigurationProperties(prefix = "features")` typed
   POJO ([phase 10](12-spring-advanced.md)); per-env overrides; change =
   redeploy/restart.
2. **@RefreshScope** — flag flips on POST `/actuator/refresh` (Config Server +
   Bus for fleet-wide — [phase 8](10-microservices.md)); change = seconds,
   no restart.
3. **Database-backed** — a `feature_flags` table + service with
   `@Cacheable("featureFlags")` reads and `@CacheEvict` on toggle: runtime
   control, per-user targeting (`enabledForUsers`), an audit trail, an admin
   UI away from product-managed rollouts. (Buy side: LaunchDarkly/Unleash.)
   Note the cache: flag checks sit on hot paths — a DB read per request is
   self-inflicted load; the evict-on-toggle keeps flips near-instant.

Uses: dark launch (code ships off), percentage/beta rollouts (canary without
infra), **kill switches** (Kafka publish misbehaving? toggle
`kafkaPublishEnabled` off — rollback in seconds, no deploy), A/B tests. Cost:
every flag is an IF forking your test matrix — flags need owners and removal
dates, or you accumulate a config-space minefield.

### 2. Blue-green — the atomic switch

Two Deployments (`slot: blue` v1, `slot: green` v2); the Service selects by
slot label. Ritual: deploy green → test it directly via a green-only Service
(real prod infra, no user traffic — the dress rehearsal) → patch the main
selector to green (atomic, all traffic) → watch → rollback is re-patching to
blue → scale blue down once confident.

Strengths: dead-simple mental model, instant binary rollback, full pre-switch
validation. Weaknesses: 2× infrastructure during transition, all-or-nothing
exposure, and **shared state undermines instant rollback** (P2).

### 3. Canary — the measured ramp

Nginx Ingress: `canary: "true"` + `canary-weight: "10"` sends 10% to
order-service-v2 (same knife Istio/ALB weighted target groups sharpen
further). Discipline: ramp 5→25→50→100 with a metrics gate at each step —
error rate, p99, business KPIs ([phase 5](07-observability.md)) compared
canary-vs-baseline; any regression → weight 0 (rollback = a number). Small
blast radius, data included; costs routing infra + genuine observability
(a canary nobody measures is just a slow rollout).

### 4. Graceful shutdown — the choreography

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`.
The SIGTERM sequence: endpoints removed (readiness) → HTTP intake stops →
in-flight requests drain → Kafka containers stop polling and **commit
offsets** → schedulers finish/cancel tasks → Hikari closes after active
queries → context destroyed, exit 0. Custom ordering when needed via a
`DisposableBean` (stop Kafka containers explicitly, cancel scheduled tasks).
The one arithmetic rule: K8s `terminationGracePeriodSeconds` (60) >
Spring's timeout (30) + buffer — SIGKILL doesn't negotiate.

### 5. Expand-contract migrations — schema changes that never stop traffic

The invariant: **every deploy window has two code versions against one
schema**, so each migration must be safe for both.

| ✅ Safe (expand) | ❌ Breaking (needs the dance) |
|---|---|
| add nullable column / with default | drop a column old code reads |
| add table | rename anything |
| add index — `CREATE INDEX CONCURRENTLY` (no table lock) | incompatible type change |
| widen enum/values | NOT NULL without default |

The rename dance (P1's antidote): (1) add `grand_total` nullable; (2) deploy
code writing both + backfill; (3) deploy code reading only `grand_total`;
(4) later release: drop `total_amount` (the contract step). Three deploys,
zero downtime — and each step individually rollback-safe.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> "Feature flags decouple deployment from release" — unpack that with three concrete capabilities.</summary>

Code reaches prod inactive (dark launch — deploy risk retired early);
activation targets users/percentages/environments (beta, canary-without-infra);
deactivation is a toggle (kill switch — rollback in seconds without a
pipeline). Bonus: enables trunk-based development — merge unfinished work
behind flags instead of long-lived branches.
</details>

<details><summary><b>Q2.</b> Blue-green mechanics in K8s and its two real weaknesses.</summary>

Two labeled Deployments (slot blue/green); the Service's selector is the
switch — patch it and 100% of traffic moves atomically; rollback = patch
back. Weaknesses: double infrastructure during transition, and shared state —
bad writes by the new version survive the traffic rollback.
</details>

<details><summary><b>Q3.</b> Canary done properly — the loop, and what it needs that blue-green doesn't.</summary>

Deploy v2 → route small weight (5–10%) → compare canary vs baseline on error
rate/p99/business metrics → ramp stepwise to 100% or zero the weight on
regression. Needs: weighted routing infra (Ingress annotations/Istio/ALB) and
real observability with per-version dimensions — the metrics gate IS the
strategy.
</details>

<details><summary><b>Q4.</b> Recite the graceful shutdown sequence and the timeout inequality.</summary>

SIGTERM → out of load balancing → stop accepting HTTP → drain in-flight (≤
timeout-per-shutdown-phase) → Kafka: stop polling, commit offsets, leave
group → schedulers wind down → Hikari drains/closes → context destroyed →
exit 0. K8s terminationGracePeriodSeconds must exceed Spring's timeout, else
SIGKILL produces every dirty-shutdown symptom at once.
</details>

<details><summary><b>Q5.</b> Why must migrations be backward compatible, and list the safe/breaking split.</summary>

Rolling/blue-green/canary all have old + new code sharing one schema
mid-rollout. Safe: additive nullable/defaulted columns, new tables, CONCURRENT
indexes, widened values. Breaking: drop, rename, incompatible retype, NOT
NULL sans default — those go through expand-contract across releases.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Zero-downtime column rename — all steps, and why each is individually rollback-safe.</summary>

(1) Add new column nullable — old code oblivious. (2) Code writes both,
backfill old rows — either version reads something valid; rolling back to
(1)'s code leaves both columns consistent-enough. (3) Code reads new only —
old column now dead weight but present. (4) Later release drops it — only
after no running version references it. Each state supports the versions
adjacent to it.
</details>

<details><summary><b>Q7.</b> Why cache DB-backed flag checks, and what's the toggle-latency story?</summary>

Flags gate hot paths — uncached, every request adds a DB read (you built a
performance problem into your safety mechanism). @Cacheable serves from
memory; @CacheEvict on toggle makes the next check re-read. Distributed
nuance: per-pod caches flip within TTL/evict propagation — near-instant, not
strictly simultaneous (fine for flags; know it).
</details>

<details><summary><b>Q8.</b> Why CREATE INDEX CONCURRENTLY, and its trade-offs?</summary>

Plain CREATE INDEX takes a lock blocking writes for the build duration —
downtime on a big table. CONCURRENTLY builds without blocking writes, at the
cost of: slower build, can't run in a transaction (Flyway: needs a
non-transactional migration), and a failed build leaves an INVALID index to
drop manually.
</details>

<details><summary><b>Q9.</b> Flag debt — what's the lifecycle discipline for feature flags?</summary>

Every flag gets an owner, an intent (release flag vs ops kill-switch vs
experiment), and a removal date. Release flags die after full rollout
(delete the IF and the old path); kill switches are few and audited;
experiments end with the analysis. Untended flags compound into 2^n untested
config states — the flag system needs a garbage collector, and it's a human.
</details>

---

## 🃏 FLASHCARDS

```
Deploy vs release	Deploy = code in prod (dark); release = flag on — separable events
Flag tiers	Properties (restart) → @RefreshScope (refresh POST) → DB+cache (runtime, per-user, audited)
Kill switch	Ops flag turning a misbehaving integration off in seconds — no pipeline
Blue-green switch	Service selector slot patch — atomic, instant back; 2× infra; state doesn't roll back
Canary loop	Weight 5→25→50→100 with metrics gate each step; rollback = weight 0
Canary prerequisites	Weighted routing (Ingress/Istio/ALB) + per-version metrics
Graceful shutdown order	Stop intake → drain HTTP → commit Kafka offsets → drain schedulers → close pool → exit
Timeout inequality	K8s terminationGracePeriod > Spring timeout-per-shutdown-phase (+buffer)
Migration invariant	Old + new code share one schema mid-rollout → every migration dual-safe
Never in one release	Drop / rename / retype / NOT NULL-no-default
Rename dance	Add → write-both+backfill → read-new → drop later (3+ deploys)
CONCURRENTLY	Index build without write lock; non-transactional; failed = INVALID index
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| flag tiers | @ConditionalOnProperty / @RefreshScope | `12-spring-advanced`, `10-microservices` |
| flag reads cached | cache-aside + evict on write | `08-caching` |
| shutdown drains consumers | offset commit + rebalance behavior | `04-kafka/02` |
| graceful shutdown ↔ probes | readiness/liveness + K8s | `07-observability`, `15-devops-docker-k8s` |
| expand-contract | Flyway immutability + API versioning rules | `13-database-advanced`, `12-spring-advanced` |
| canary metrics gate | p99/error-rate observability | `07-observability` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
