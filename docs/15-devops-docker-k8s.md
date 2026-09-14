# DevOps — Docker, Compose, Kubernetes, CI/CD
**⬜ Not Started · plan: Phase 13, Tasks 54–57** — Multi-stage builds, local stacks, K8s manifests, GitHub Actions

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** The whole phase is one pipeline: **code → immutable image →
declared desired state → automated promotion**. Docker freezes the app + JRE
into an image; Compose declares the local universe (app + Postgres + Kafka +
Redis); Kubernetes runs the same image N times and *reconciles reality toward
your YAML* (you never "start pods" — you declare 3 replicas and K8s makes it
so); CI/CD is the conveyor: test → build/push image → `kubectl set image` →
watch the rollout.

```
Dockerfile:  [builder: maven+JDK → jar]  →  [runtime: JRE-alpine + non-root user + jar]  ~180MB
K8s:         Deployment(3 replicas, RollingUpdate maxUnavailable:0) ← probes gate traffic
             Service(ClusterIP) → pods | ConfigMap(config) + Secret(credentials) | HPA(2–10 @70% CPU)
CI:          push → test job → build+push :git-sha → kubectl set image → rollout status
```

**Five rules you must never get wrong:**
1. Multi-stage: build with JDK+Maven, ship only the jar on a JRE base — 3× smaller, no compilers in prod, plus the **pom-first COPY** so dependency layers cache.
2. Containers run **non-root** (`USER appuser`) with memory-aware JVM flags.
3. Zero-downtime = RollingUpdate `maxUnavailable: 0` + **readiness probes** + graceful shutdown, with `terminationGracePeriodSeconds ≥` Spring's shutdown timeout.
4. K8s Secrets are **base64, not encryption** — prod secrets come from Vault/External Secrets; config in ConfigMaps, credentials in Secrets, never in images.
5. Images are tagged with the **git SHA** (immutable, traceable, rollback-able) — `latest` is not a deployment strategy.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Dockerfile copies <code>COPY . .</code> then runs <code>mvn package</code>, single stage on a maven:jdk image. Name the three distinct problems versus the reference Dockerfile — and which one bites on EVERY code change?</summary>

(1) Image ships JDK + Maven + source + ~/.m2 — 600MB+, larger attack surface.
(2) Root user by default — container escape = root. (3) The one that bites
every change: `COPY . .` before dependency resolution invalidates the layer
cache, so **all dependencies re-download on every build**. The reference
copies pom.xml → `dependency:go-offline` → then src: deps re-fetch only when
the pom changes. Layer-cache ordering is the difference between 30s and 8min
builds.
</details>

<details>
<summary><b>P2.</b> Deployment has RollingUpdate maxUnavailable:0, but no readinessProbe. You roll out v2, which takes 40s to start. What do users experience during the rollout, and why didn't maxUnavailable:0 save you?</summary>

Errors for ~40s per pod: K8s considers a pod "ready" the moment its container
runs — without a readiness probe, traffic routes to a JVM that's still
starting Spring, connection-refused/503s ensue. maxUnavailable governs POD
COUNT, not traffic-worthiness; only the readiness probe tells the Service
"don't send traffic yet." Zero-downtime needs all three legs: rolling
strategy + readiness gate + graceful shutdown for the old pods.
</details>

<details>
<summary><b>P3.</b> Rollout of v2 goes bad in prod (errors spiking). The image was pushed as both <code>:latest</code> and <code>:git-sha</code>. Compare your rollback options if the Deployment referenced :latest vs :sha.</summary>

With :sha — `kubectl rollout undo` flips to the previous ReplicaSet's exact
pinned image; deterministic, seconds. With :latest — undo "changes" the tag to
the same string; whether pods pull old or new bytes depends on cached layers
and imagePullPolicy — you genuinely can't say what's running. Mutable tags
destroy both rollback and auditability, which is why CI pushes the SHA and
deploys the SHA.
</details>

---

## 📖 THE STORY

### 1. The Dockerfile, line by line of intent

```dockerfile
FROM maven:3.9-openjdk-17-slim AS builder
COPY pom.xml .
RUN mvn dependency:go-offline -q      # cached until pom.xml changes
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine    # ~180MB vs ~600MB
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/*.jar app.jar
USER appuser
ENTRYPOINT ["java", "-Xmx512m", "-Xms256m", "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
```

The four intents: **stage split** (build tools never ship), **layer-cache
ordering** (P1), **non-root** (defense in depth), **explicit JVM sizing**
(-Xmx must respect the container's memory limit — or use
`-XX:MaxRAMPercentage` to derive it). `docker history` shows your layers;
tag+push with registry/name:version.

### 2. Compose — the laptop platform

One file declares app + postgres (volume for data, `pg_isready` healthcheck) +
redis (LRU-capped) + zookeeper/kafka (the dual-listener setup from
[task 10](04-kafka/01-fundamentals-partitions-groups.md)) + kafka-ui, all on one bridge network where
**service names are hostnames** (`jdbc:postgresql://postgres:5432/...`).

The subtlety worth knowing: `depends_on.condition: service_healthy` gates on
the healthcheck, not just container start — Postgres accepts connections
seconds after the process launches, and the app's own restart/retry still has
to tolerate slow Kafka. Daily verbs: `up -d`, `logs -f order-service`,
`exec postgres psql...`, `down` (`-v` to also wipe volumes).

### 3. Kubernetes — declare, reconcile, probe

The manifest set and each one's job:
- **Deployment** — desired state: 3 replicas of image :sha; RollingUpdate
  `maxSurge: 1, maxUnavailable: 0`; env from ConfigMap
  (`configMapKeyRef`) and Secret (`secretKeyRef`); **resources**
  (requests = scheduling reservation, limits = throttle/OOM ceiling — JVM
  -Xmx must fit under the memory limit); **readinessProbe** →
  `/actuator/health/readiness`, **livenessProbe** → `/liveness`
  (the [phase 5](07-observability.md) distinction, now enforced by the
  platform); `terminationGracePeriodSeconds: 60` ≥ Spring graceful-shutdown
  window.
- **Service** (ClusterIP) — stable virtual IP/DNS over the churning pods;
  external traffic enters via Ingress.
- **ConfigMap vs Secret** — config vs credentials; Secret base64 ≠ encrypted →
  real secrets via External Secrets Operator / Vault / Sealed Secrets, RBAC on
  top.
- **HPA** — 2–10 replicas targeting 70% CPU / 80% memory; autoscaling is why
  pods must be stateless and start fast.

Rollout verbs: `kubectl set image ... && kubectl rollout status`;
`kubectl rollout undo` = instant revert to the previous ReplicaSet.

### 4. Graceful shutdown — the full handshake

SIGTERM → K8s simultaneously removes the pod from Service endpoints → Spring
(`server.shutdown: graceful`) stops accepting, drains in-flight requests,
executors finish queued tasks (`waitForTasksToCompleteOnShutdown` —
[task 8](03-async-and-scheduling/01-thread-pools-completablefuture.md)), Kafka consumers commit and leave the group
cleanly (no rebalance storm — [task 11](04-kafka/02-reliability-dlt-idempotency.md)) → exit. If the
grace period is shorter than the drain, SIGKILL cuts requests mid-flight —
hence `terminationGracePeriodSeconds ≥` app timeout.

### 5. CI/CD — the conveyor

Three chained jobs: **test** (JDK 17 + Maven cache + a Postgres *service
container* — real DB in CI, echoing the TestContainers philosophy), **build**
(main only: docker login → build-push-action tagging `:latest` + `:git-sha`),
**deploy** (kubeconfig from secrets → `kubectl set image` with the SHA →
`rollout status --timeout=5m` so a failed rollout **fails the pipeline** —
the gate that turns bad deploys into red builds instead of incidents).

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Why multi-stage builds — the three wins?</summary>

Size (JRE-alpine runtime ~180MB vs 600MB+ with JDK/Maven — faster pulls,
cheaper storage); security (no compilers/build tools/source in the shipped
image); cache discipline (pom-first COPY keeps dependency layers stable so
code changes rebuild in seconds).
</details>

<details><summary><b>Q2.</b> Spell out the zero-downtime deployment recipe.</summary>

RollingUpdate with maxUnavailable: 0 (old pod stays until replacement is
READY) + maxSurge: 1; readinessProbe gating Service traffic (no requests to
starting JVMs); graceful shutdown draining old pods with
terminationGracePeriodSeconds ≥ Spring's timeout; and rollout status as the
pipeline gate.
</details>

<details><summary><b>Q3.</b> ConfigMap vs Secret — and why "Secrets aren't secret."</summary>

ConfigMap: non-sensitive config (URLs, profiles). Secret: credentials —
but K8s Secrets are base64-encoded, not encrypted; anyone with read RBAC
decodes them. Production: External Secrets Operator/Vault/Sealed Secrets,
etcd encryption at rest, tight RBAC.
</details>

<details><summary><b>Q4.</b> Resource requests vs limits, and the JVM interaction.</summary>

Requests = what the scheduler reserves (placement); limits = hard ceiling
(CPU throttled, memory → OOMKill). The JVM must be told: -Xmx (or
MaxRAMPercentage) safely below the memory limit, or the kernel kills the
container the JVM thought had headroom.
</details>

<details><summary><b>Q5.</b> Walk the graceful-shutdown handshake K8s ↔ Spring.</summary>

SIGTERM + endpoint removal → Spring graceful: stop accepting, drain in-flight
HTTP, finish executor queues, Kafka consumers commit offsets and leave the
group → clean exit before terminationGracePeriodSeconds, else SIGKILL.
Grace period ≥ app drain timeout is the invariant.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Blue-Green vs Canary (preview for phase 14) — mechanics and cost profile.</summary>

Blue-Green: full duplicate environment, switch 100% of traffic atomically,
rollback = switch back — instant but doubles infra during transition. Canary:
route 1–10% to the new version, watch metrics, ramp — small blast radius,
needs weighted routing (Istio/ALB) and automated analysis.
</details>

<details><summary><b>Q7.</b> Why does the CI test job use a Postgres service container instead of H2?</summary>

Same reason as TestContainers locally (phase 3): validate against the real
engine — dialect, constraints, locking. Service containers are GitHub
Actions' native way to stand up real dependencies for a job; the pipeline
should prove what production runs.
</details>

<details><summary><b>Q8.</b> What does `kubectl rollout undo` actually do, and what makes it trustworthy?</summary>

Re-activates the previous ReplicaSet (Deployments keep revision history) —
scaling old up, new down, under the same rolling rules. Trustworthy only when
image tags are immutable (SHA): the old ReplicaSet references exact bytes.
With :latest, "previous" may resolve to anything.
</details>

<details><summary><b>Q9.</b> HPA at 70% CPU — what app properties does autoscaling silently assume?</summary>

Stateless pods (no session/local state — scale-in kills pods arbitrarily),
fast startup (scale-out must land before the spike passes — JVM warmup
matters), external state shared (DB/Redis/Kafka), and graceful shutdown
(scale-in drains). Autoscaling is an architecture test disguised as a config.
</details>

---

## 🃏 FLASHCARDS

```
Multi-stage split	Builder (JDK+Maven → jar) / runtime (JRE-alpine + jar only)
Layer-cache trick	COPY pom.xml + dependency:go-offline BEFORE COPY src
Container security basics	Non-root USER, JRE-only base, no secrets in image
JVM in a container	-Xmx (or MaxRAMPercentage) below the K8s memory limit
Compose networking	Service names are hostnames on the shared bridge network
depends_on gotcha	condition: service_healthy gates on healthcheck, not process start
Zero-downtime trio	maxUnavailable:0 + readinessProbe + graceful shutdown (grace ≥ drain)
Probes recap	Readiness gates traffic; liveness triggers restart — never dependencies in liveness
K8s Secret reality	base64 ≠ encryption — Vault/External Secrets + RBAC in prod
Image tagging	Push+deploy the git SHA; :latest breaks rollback determinism
Pipeline gate	kubectl rollout status --timeout → failed rollout = red build
Requests vs limits	Scheduler reservation vs OOM/throttle ceiling
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| readiness/liveness endpoints | Actuator probe groups | `07-observability` |
| graceful shutdown drain | executor + consumer shutdown | `03-async-and-scheduling/01`, `04-kafka/02` |
| real DB in CI | TestContainers philosophy | `05-testing` |
| env-var config | prod profile discipline | `12-spring-advanced` |
| rollout strategies | Blue-Green/Canary in depth | `16-production-hardening` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
