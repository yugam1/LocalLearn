# Kafka Advanced — Rebalancing, DLT, Idempotency, Offsets
**✅ Completed · plan: Phase 2, Task 11** — Rebalance mechanics, @RetryableTopic/DLT, idempotent + batch consumers, offset ops

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Everything in advanced Kafka is a response to two forces:
**membership changes** (rebalancing redistributes partitions — a stop-the-world
event) and **at-least-once delivery** (duplicates and poison messages *will*
happen). The mature consumer is armored on three sides: retries + DLT so one
bad message can't block a partition, an eventId ledger so duplicates are no-ops,
and lag monitoring so falling behind is visible before customers notice.

```
poison pill without DLT:  [bad msg] FAIL→retry→FAIL→retry→... ← partition FROZEN
with @RetryableTopic:     main → retry-0(1s) → retry-1(2s) → retry-2(4s) → topic.dlt → ack, moves on
```

**Five rules you must never get wrong:**
1. Rebalance = stop-the-world for the group; uncommitted work gets reprocessed after. **Sticky assignor** minimizes partition movement.
2. DLT pattern: bounded retries with exponential backoff, then park in `.dlt` and **commit** — unblocking beats completeness; humans handle the parked ones.
3. Retry *transient* errors only — validation errors (`IllegalArgumentException`) go **straight to DLT**; retrying a permanently bad message is theater.
4. Idempotent consumer = `processed_events` table with **unique eventId**, checked-and-inserted in the **same @Transactional** as the business work.
5. **Lag** = log-end-offset − committed-offset, per partition. It's THE consumer health metric; alert on it.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Idempotent consumer, but the developer saves ProcessedEvent(eventId) BEFORE calling <code>processOrder(event)</code>, in the same transaction... and a colleague "optimizes" by moving the save outside the transaction, still before processing. What breaks in each version?</summary>

Same-transaction version: **nothing breaks** — order inside one atomic
transaction doesn't matter; if processing throws, the whole tx (including the
ledger insert) rolls back, redelivery retries cleanly. Outside-the-transaction
version: ledger says "done" the moment it commits — if processing then fails,
redelivery finds the eventId and **skips a message that was never processed**.
Permanent, silent loss. The atomicity, not the ordering, is the load-bearing part.
</details>

<details>
<summary><b>P2.</b> @RetryableTopic(attempts=4, backoff 1s ×2). A message fails validation with IllegalArgumentException, which is in <code>exclude</code>. Trace its path. And a message that fails with a flaky-HTTP RuntimeException all 4 times?</summary>

Validation failure: skips all retry topics, lands **directly in `.dlt`** —
excluded exceptions are "retry cannot help." Flaky-HTTP: main fails →
`-retry-0` after 1s → `-retry-1` after 2s → `-retry-2` after 4s → `.dlt`.
Either way the main partition committed and moved on immediately — that's the
whole point.
</details>

<details>
<summary><b>P3.</b> Batch listener gets 100 events, event #73 throws, nothing was acked. What gets reprocessed after redelivery, and what does that demand of your processing code?</summary>

**All 100** — the batch has one collective ack; no ack means the entire poll is
redelivered. So batch handlers must tolerate re-seeing events 1–72: idempotency
per event (the ledger), or per-item try/catch with your own bookkeeping. Batch
mode amplifies the at-least-once duplicate window in exchange for ~5–10× commit
throughput.
</details>

---

## 📖 THE STORY

### 1. Rebalancing — the price of elasticity

Triggers: consumer joins/leaves/crashes, heartbeat timeout, subscription or
partition-count change. The group coordinator halts **every** consumer in the
group, recomputes assignments, resumes. Two costs: a processing gap, and
duplicates (anything processed-but-uncommitted at the freeze is redone).

Assignors: `Range` (default; clumps, imbalanced across topics), `RoundRobin`
(even, but reshuffles almost everything on change), **`Sticky`** (even AND
minimal movement — consumers keep most partitions, so caches/state survive and
the duplicate window shrinks). Production = Sticky (or CooperativeSticky, which
also removes most of the stop-the-world).

### 2. Offset strategy ladder

1. **Auto-commit** — timer-based, decoupled from processing: can lose AND
   duplicate ([task 10 P2](01-fundamentals-partitions-groups.md)). Never for business events.
2. **Manual per-message** — ack after each: safest bookmark, one commit RTT per
   message.
3. **Manual per-batch** — process N, ack once: the production default; 5–10×
   fewer commits, wider duplicate window on failure (P3).

### 3. DLT — quarantine, don't blockade

One un-processable message with naive "no ack, redeliver" = the partition
freezes forever behind it. `@RetryableTopic` builds the escape route
declaratively:

```java
@RetryableTopic(
    attempts = "4",                                    // 1 original + 3 retries
    backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
    dltTopicSuffix = ".dlt",
    include = {Exception.class},
    exclude = {IllegalArgumentException.class})        // hopeless → DLT immediately
@KafkaListener(topics = "order.processed", groupId = "order-processing-group")
public void processWithRetry(OrderCreatedEvent event) { ... }

@DltHandler
public void handleDlt(OrderCreatedEvent event,
                      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                      @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error) {
    saveToDltDatabase(event, topic, error);   // reviewable
    sendOpsAlert(event, error);               // actionable
}
```

Mechanics worth saying in an interview: each retry tier is a **separate topic**
(`-retry-0`, `-retry-1`, ...) — the delay is achieved by *when that topic's
consumer runs*, and the main partition committed immediately. The DLT handler's
job is triage: persist, alert, metric. A DLT nobody watches is just a nicer
place to lose data.

### 4. The idempotency ledger

At-least-once ⇒ duplicates ⇒ the consumer must make "twice" equal "once":

```java
@KafkaListener(topics = "order.confirmed", groupId = "idempotent-group")
@Transactional                                   // ledger + work = atomic
public void consumeIdempotent(OrderCreatedEvent event, Acknowledgment ack) {
    if (processedEventRepo.existsByEventId(event.getEventId())) {
        ack.acknowledge();                       // still advance the bookmark!
        return;
    }
    processOrder(event);
    processedEventRepo.save(ProcessedEvent.builder()
        .eventId(event.getEventId()) /* + type, topic, correlationId, duration */.build());
    ack.acknowledge();
}
```

Three details carry the design: **unique index** on event_id (the DB — not the
check — is the real guard against a race between two duplicates: same
"invariant in the database" move as everywhere else); the **shared
transaction** (P1); and a **3 a.m. cleanup job** pruning rows older than 30
days so the ledger doesn't grow forever.

### 5. Batch consumption & custom partitioning

Batch: `listener.type: batch` + `max-poll-records: 100`; the listener takes
`List<OrderCreatedEvent>` (+ parallel lists of partitions/offsets) and acks
once. Use when throughput matters and per-event idempotency is in place.

Custom `Partitioner` (registered via `PARTITIONER_CLASS_CONFIG`): business
routing — premium keys → dedicated partition (isolated latency), bulk → another,
default hash for the rest. Rare, but the interview point is *why*: partition =
both an ordering domain and a capacity slice, so routing = QoS.

### 6. Operating consumers — lag, pause, replay

- **Lag** = `LOG_END_OFFSET − CURRENT_OFFSET` per partition; see it with
  `kafka-consumer-groups --describe --group order-service-group`. Alert on
  absolute lag and lag *age*.
- **Pause/resume** — `KafkaListenerEndpointRegistry.getAllListenerContainers()
  .pause()/.resume()`: the ops-emergency valve (e.g., stop consuming while a
  downstream DB is being restored). Backpressure at the platform level, same
  spirit as CallerRunsPolicy ([task 8](../03-async-and-scheduling/01-thread-pools-completablefuture.md)).
- **Replay** — stop the group, then
  `--reset-offsets --to-earliest | --to-offset N | --to-datetime T --execute`.
  Replay is Kafka's superpower and the reason idempotency is non-negotiable:
  a replay IS mass deliberate duplication.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> What triggers a rebalance, what happens during one, and how do you soften it?</summary>

Triggers: member join/leave/crash, heartbeat timeout, partition/subscription
changes. During: coordinator freezes the whole group, reassigns, resumes —
processing gap + reprocessing of uncommitted work. Soften: Sticky/
CooperativeSticky assignor (minimal partition movement), sane session/heartbeat
timeouts, graceful shutdown so leaves are announced.
</details>

<details><summary><b>Q2.</b> Walk the DLT pattern end to end, including why the partition unblocks.</summary>

Failure → bounded retries on separate retry topics with exponential backoff
(1s→2s→4s) → still failing → published to topic.dlt and OFFSET COMMITTED on the
source — the partition moves on immediately; the bad message now lives where a
@DltHandler persists it, alerts ops, increments a metric. Non-retryable
exceptions skip straight to DLT.
</details>

<details><summary><b>Q3.</b> Implement an idempotent consumer — the three load-bearing details.</summary>

processed_events table with UNIQUE event_id; check-then-process-then-insert all
inside ONE @Transactional (fail → ledger row rolls back → clean redelivery);
duplicate path still calls ack.acknowledge() to advance the offset. Unique
index is the true race guard; plus periodic ledger cleanup.
</details>

<details><summary><b>Q4.</b> What is consumer lag, and what two alerts do you set on it?</summary>

Per-partition log-end-offset minus committed offset = unprocessed backlog.
Alert on absolute size (lag > N messages) and on age (oldest unprocessed
message > T minutes) — a small-but-stale lag on a quiet topic is still an
outage signal.
</details>

<details><summary><b>Q5.</b> Batch vs per-message commit — numbers and the trade.</summary>

Per-message: N commit round trips, smallest duplicate window. Batch: 1 commit
per poll (~5–10× throughput), but any failure redelivers the whole batch —
so batch mode requires per-event idempotency.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why is each @RetryableTopic delay tier a separate topic rather than a sleep?</summary>

Sleeping in the listener would block the partition (and the consumer thread) —
recreating the poison-pill problem. Separate topics let the main flow commit
instantly; delay is enforced by when the retry topic's consumer processes the
message. Ordering across the retry path is sacrificed — acceptable for
error handling.
</details>

<details><summary><b>Q7.</b> When is pause/resume the right tool, and what happens to the group while paused?</summary>

Downstream outage (DB restore, dependency down): pausing stops poll-processing
without leaving the group — no rebalance, no lost assignment; lag simply grows.
Resume picks up where it stopped. Contrast with stopping the app: that triggers
rebalance and, at scale, a rebalance storm.
</details>

<details><summary><b>Q8.</b> Replay from last Tuesday 10:00 — exact procedure and the precondition.</summary>

Stop all consumers in the group (offsets can't be reset while active) →
`kafka-consumer-groups --group G --topic T --reset-offsets --to-datetime
2024-...T10:00:00.000 --execute` → restart. Precondition: consumers idempotent —
replay is deliberate mass duplication; also retention must still hold that data.
</details>

<details><summary><b>Q9.</b> Why does the eventId ledger need the unique index if the code already checks existsByEventId?</summary>

Two consumers (rebalance edge, duplicate delivery to different threads) can
both pass the exists() check before either inserts — TOCTOU race. The unique
constraint makes the second insert fail atomically in the DB, and that
transaction rolls back harmlessly. Check-then-act needs an atomic arbiter —
same lesson as 02-concurrency/03, one layer up.
</details>

---

## 🃏 FLASHCARDS

```
Rebalance in one line	Membership change → group-wide freeze → partitions redealt → resume (dupes possible)
Production assignor	Sticky/CooperativeSticky — minimal partition movement
DLT flow	Bounded backoff retries (separate retry topics) → .dlt → COMMIT → partition unblocked
Straight-to-DLT exceptions	Non-retryable (validation) — listed in exclude; retrying can't help
@DltHandler duties	Persist + alert + metric — unwatched DLT = polite data loss
Idempotency ledger	UNIQUE eventId row inserted in SAME transaction as the work
Duplicate arrives	Skip work, STILL ack (advance offset)
Ledger real race guard	The unique index, not the exists() check (TOCTOU)
Lag formula + alerts	log-end − committed, per partition; alert on size AND age
Batch failure semantics	Whole batch redelivered → per-event idempotency required
Replay command	--reset-offsets --to-earliest|--to-offset|--to-datetime --execute (group stopped)
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| unique-index vs exists() check | check-then-act races → atomic arbiter | `02-concurrency/03` |
| ledger + work in one tx | transaction atomicity | `01-foundations/05` |
| pause/resume backpressure | CallerRunsPolicy self-throttling | `03-async-and-scheduling/01` |
| exactly-one-runs in cluster | ShedLock | `03-async-and-scheduling/02` |
| retry + backoff + give-up | Resilience4j Retry/CircuitBreaker | `09-resilience` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
