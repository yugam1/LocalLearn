# Kafka — Producer & Consumer Basics
**✅ Completed · plan: Phase 2, Task 10** — Topics, partitions, consumer groups, offsets, acks, Spring Kafka wiring

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Kafka is a **durable, replayable log**, not a queue. Messages
aren't consumed away — they sit on disk with retention; each consumer *group*
just remembers a bookmark (**offset**) per partition. Ordering exists only
**within a partition**, and the message **key** decides the partition — so
"events about the same order stay ordered" costs exactly one decision:
key = orderId.

```
topic order.created (3 partitions)
 P0: [e1][e4][e7]  ← ordered           group "order-service": A→P0, B→P1, C→P2 (work sharing)
 P1: [e2][e5]      ← ordered           group "analytics":     D→P0,P1,P2      (independent copy)
 P2: [e3][e6]      ← ordered
 cross-partition order: NONE           each group keeps its own offsets
```

**Five rules you must never get wrong:**
1. **Partition = ordering unit AND parallelism unit.** One partition ↔ at most one consumer *per group*; consumers beyond partition count sit idle.
2. Same key → same partition → ordered. No key → round-robin/sticky, no cross-event order.
3. Reliability trio for critical events: producer `acks=all` + `enable-idempotence=true`; consumer `enable-auto-commit=false` + manual `ack.acknowledge()` after processing.
4. Commit-after-process = **at-least-once** → duplicates are possible → consumers must be idempotent (eventId, [task 11](02-reliability-dlt-idempotency.md)).
5. Different consumer groups each get **all** messages — that's fan-out (order-service reacts, analytics counts), not competition.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Topic has 3 partitions. You scale the consumer group from 3 to 6 consumers "for throughput." What do consumers 4–6 do?</summary>

**Nothing — they idle.** Each partition goes to exactly one consumer in the
group; with 3 partitions there are 3 assignments. Extra consumers are hot
spares only. Throughput past that needs more partitions (decided at design
time — repartitioning breaks key→partition mapping and thus ordering history).
</details>

<details>
<summary><b>P2.</b> auto-commit on (default 5s interval). Consumer polls 50 records, commits at t=5s, crashes at t=6s having processed only 20. What happened to records 21–50? And in the reverse timing (crash before the commit)?</summary>

Records 21–50 are **lost for this group** — their offsets were committed as done
though never processed; the restarted consumer resumes past them. Reverse
timing: everything since the last commit is **reprocessed** (duplicates).
Auto-commit's timer is uncorrelated with your processing — that's why it can
produce *both* loss and duplicates, and why the setting is always
`enable-auto-commit: false` + manual ack after success.
</details>

<details>
<summary><b>P3.</b> Producer sends OrderCreated, OrderUpdated, OrderCancelled for order #42 with <b>no key</b>. The consumer group has 3 consumers. Can "cancelled" be processed before "created"?</summary>

**Yes.** No key → events spread across partitions → three different consumers
process them concurrently, in any relative order. A cancellation for an order
that "doesn't exist yet" downstream is exactly the bug. Key = orderId puts all
three in one partition, processed by one consumer, in publish order.
</details>

---

## 📖 THE STORY

### 1. Log, not queue — the mindset shift

A queue deletes on ack; Kafka **retains** (per-topic `retention.ms`, e.g. 7d for
order.created, 30d for cancellations that drive refunds). Consumption = moving
your group's offset bookmark forward. This buys: replay (reset offsets — rebuild
a projection, re-run a broken consumer), multiple independent readers, and
crash recovery that's just "resume from bookmark."

### 2. The topology: topic → partitions → groups

- **Topic** — named channel (`order.created`).
- **Partition** — an append-only ordered file; the topic is their union.
  Parallelism = partition count, forever-ish: choose 3 here, more in prod.
- **Consumer group** — one logical subscriber. Within it, partitions are dealt
  out (rebalanced on join/leave/crash). Across groups, everyone gets everything —
  `order-service-group` fulfills while `analytics-group` counts, same messages,
  separate offsets.

### 3. The producer's reliability dials

| Setting | Choice | Why |
|---|---|---|
| `acks` | `all` | leader + all in-sync replicas confirm — survives leader crash; `1` = leader only; `0` = fire-and-forget |
| `enable-idempotence` | `true` | producer sequence numbers → broker drops retry-duplicates (needs acks=all) |
| `retries` | 3+ | transient broker errors self-heal |
| `batch-size` + `linger-ms` | 16KB / 10ms | wait a beat, ship a batch — throughput for tiny latency |
| `compression-type` | snappy | cheap CPU, big network win on JSON |

Send is async — always attach the callback:

```java
kafkaTemplate.send(topic, event.getOrderId().toString(), event)   // key = orderId!
    .whenComplete((result, ex) -> {
        if (ex == null) log.info("partition={}, offset={}",
            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        else log.error("Failed to publish orderId={}", event.getOrderId(), ex);
    });
// .get() on the future = synchronous send — only when the request must not return before confirm
```

### 4. The consumer's reliability dials

```yaml
consumer:
  group-id: order-service-group
  auto-offset-reset: earliest      # no committed offset yet? start from the beginning
  enable-auto-commit: false        # WE decide when it's done
  max-poll-records: 50
listener:
  ack-mode: manual
  concurrency: 3                   # 3 listener threads ≈ one per partition
```

```java
@KafkaListener(topics = ORDER_CREATED_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
public void consume(@Payload OrderCreatedEvent event,
                    @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                    @Header(KafkaHeaders.OFFSET) long offset,
                    Acknowledgment ack) {
    if (event.getCorrelationId() != null) MDC.put("correlationId", event.getCorrelationId());
    try {
        process(event);
        ack.acknowledge();          // commit ONLY after success
    } catch (Exception e) {
        log.error("Failed — will be redelivered: orderId={}", event.getOrderId(), e);
        // no ack → redelivery from last committed offset
    } finally { MDC.clear(); }
}
```

`auto-offset-reset` matters once per group: `earliest` = process history
(safe default for event handlers), `latest` = only new events (metrics tails).
Deserialization armor: `ErrorHandlingDeserializer` wrapping JsonDeserializer —
a poison payload fails *handleably* instead of crash-looping the container
(the DLT story continues in [task 11](02-reliability-dlt-idempotency.md)).

### 5. Event design

`BaseEvent`: `eventId` (UUID — the consumer's idempotency key), `eventType`,
`eventTimestamp`, `correlationId` (MDC bridge — [task 7](../01-foundations/07-logging-mdc-correlation-ids.md)),
`userId`. Subtypes carry business payload (OrderCreatedEvent with items,
OrderUpdatedEvent with old/new status...). Events are **facts, past tense** —
they describe what happened, they don't command.

Topics are declared as `NewTopic` beans (`TopicBuilder.name(...).partitions(3)
.replicas(1).config("retention.ms", ...)`) — infrastructure as code, though in
locked-down prod clusters topics are usually pre-provisioned.

### 6. Wiring into createOrder — and the honest gap

After `orderRepository.save(...)`, build the event (eventId, correlationId from
MDC, payload from the saved order) and publish. Note the gap you'll close in
task 12: save-then-publish is **two systems, no shared transaction** — commit
can succeed and publish fail (event lost) or publish succeed and commit roll
back (phantom event). The fix is the **outbox pattern**; for now, know the gap
exists — interviewers love it.

---

## 🛠 BUILD REFERENCE

<details>
<summary><b>Docker Compose (Kafka + Zookeeper + UI)</b></summary>

`docker-compose-kafka.yml`: confluentinc/cp-zookeeper:7.5.0 (port 2181),
cp-kafka:7.5.0 with dual listeners — `PLAINTEXT://kafka:29092` (in-network) and
`PLAINTEXT_HOST://localhost:9092` (host apps) — and provectuslabs/kafka-ui on
http://localhost:8090.

```bash
docker-compose -f docker-compose-kafka.yml up -d
```

The dual-listener setup is the classic "works in container, times out from
host" fix: clients must be handed an address they can actually reach
(`KAFKA_ADVERTISED_LISTENERS`).
</details>

<details>
<summary><b>Producer/consumer factory beans</b></summary>

Producer: `DefaultKafkaProducerFactory` with StringSerializer key /
JsonSerializer value, acks=all, idempotence, batching, snappy;
`ADD_TYPE_INFO_HEADERS=false` (don't leak Java class names into headers).

Consumer: `DefaultKafkaConsumerFactory` with `ErrorHandlingDeserializer`
wrapping String/Json deserializers, `TRUSTED_PACKAGES` limited to
`com.ecommerce.orderservice.event` (deserialization gadget safety),
`VALUE_DEFAULT_TYPE` as fallback. Listener container factory: MANUAL ack mode,
concurrency 3, `DefaultErrorHandler`.
</details>

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> How does Kafka guarantee ordering, and what's the design decision that controls it?</summary>

Ordering is guaranteed only within one partition. The message key routes to a
partition (hash(key) % partitions), so choosing the key = choosing the ordering
scope: key=orderId → all of an order's events serialize; no key → no
cross-event ordering.
</details>

<details><summary><b>Q2.</b> Consumer group mechanics: 3 partitions with 2, 3, and 6 consumers?</summary>

2 consumers: one takes two partitions. 3: one each — max useful parallelism.
6: three idle (a partition never splits across a group). Scaling reads beyond
that requires more partitions. Different groups are unaffected — each gets the
full stream.
</details>

<details><summary><b>Q3.</b> Why enable-auto-commit=false — what two failure modes does auto-commit create?</summary>

Auto-commit fires on a timer, decoupled from processing: commit-before-finish +
crash = messages marked done but never processed (loss); finish-before-commit +
crash = reprocessing (duplicates). Manual ack after success removes the loss
mode, leaving only duplicates → at-least-once + idempotent consumer.
</details>

<details><summary><b>Q4.</b> acks=0/1/all + producer idempotence — what does each buy?</summary>

0: no confirmation, can lose silently. 1: leader confirmed — lost if leader
dies before replication. all: every in-sync replica has it — survives leader
crash; pair with enable-idempotence (broker dedups producer retries via
sequence numbers) for exactly-once *on the produce side*.
</details>

<details><summary><b>Q5.</b> Consumer crashes without committing — what happens on restart, and what does that force on your handler code?</summary>

It resumes from the last committed offset and reprocesses everything after it.
So the handler must be idempotent — same event twice, same end state (check
eventId / natural key before acting).
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Kafka vs RabbitMQ — when each?</summary>

Kafka: disk-persistent replayable log, pull-based, partitioned ordering, huge
throughput, many independent readers — event streaming, audit, analytics.
RabbitMQ: push-based broker, rich routing (exchanges), message gone after ack —
classic task queues and RPC-ish workloads.
</details>

<details><summary><b>Q7.</b> What do batch-size and linger-ms trade, exactly?</summary>

Latency for throughput: linger-ms holds a send up to N ms hoping to fill
batch-size bytes; full batch = fewer, bigger, compressed-together requests.
linger 10ms typically multiplies throughput for imperceptible delay.
</details>

<details><summary><b>Q8.</b> Why does the consumer config restrict trusted.packages?</summary>

JSON deserialization that instantiates arbitrary classes from message metadata
is a remote-code-execution vector. Whitelisting the event package means a
malicious/corrupt header can't make the consumer construct unexpected types.
</details>

<details><summary><b>Q9.</b> The save-then-publish gap: name both failure orders and the pattern that closes it.</summary>

(a) DB commit ok, publish fails → downstream never learns (lost event).
(b) publish ok, DB rolls back → downstream reacts to an order that doesn't
exist (phantom). Fix: outbox pattern — event written to an outbox table in the
SAME transaction, a relay publishes it later (task 12/phase 8).
</details>

---

## 🃏 FLASHCARDS

```
Kafka in one phrase	Durable replayable log — consumers move bookmarks, messages stay
Ordering scope	One partition; key routes to partition → key = ordering decision
Partition:consumer within a group	1:max-1 — extra consumers idle; parallelism ceiling = partitions
Different consumer groups	Each gets ALL messages, own offsets (fan-out, not competition)
Critical-event producer settings	acks=all + enable-idempotence=true (+ retries)
Critical-event consumer settings	enable-auto-commit=false, manual ack AFTER processing
Auto-commit's two failure modes	Loss (commit before process + crash) and duplicates (reverse)
Commit-after-process delivery	At-least-once → handler must be idempotent (eventId)
auto-offset-reset	earliest = process history; latest = only new (per-group, first connect)
ErrorHandlingDeserializer	Poison message fails handleably instead of crash-looping the listener
Save-then-publish gap	Dual-write problem → outbox pattern
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| offset bookmark + replay | @Version/eventId — state as append-only facts | `10-microservices` (event sourcing) |
| idempotent consumer need | idempotency keys everywhere at-least-once exists | `04-kafka/02` |
| correlationId in events | MDC propagation | `01-foundations/07` |
| listener concurrency | thread pools per concern | `03-async-and-scheduling/01` |
| dual-write gap | transaction boundaries | `01-foundations/05` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
