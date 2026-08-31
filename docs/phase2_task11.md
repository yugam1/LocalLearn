# Phase 2 — Task 11: Kafka Advanced — Consumer Groups, DLT, Idempotency
**Estimated Time:** 1.5 hours
**Status:** ✅ Completed

---

## 🎯 What You Learn
1. Consumer group rebalancing — triggers, process, impact
2. Partition assignment strategies (Range, RoundRobin, Sticky)
3. Custom partitioner — business-rule-based routing
4. Dead Letter Topics (DLT) — handling poison pills
5. @RetryableTopic — retry with exponential backoff
6. Idempotent consumers — preventing duplicate processing
7. Batch consumption — high-throughput processing
8. Offset management — inspect, pause, resume, reset
9. Consumer lag monitoring
10. Processed event tracking in database

---

## 🧠 Core Concepts

### Consumer Group Rebalancing
**Triggers:** consumer joins, consumer crashes, heartbeat timeout, partition count change.

```
Before (2 consumers, 3 partitions):
  Consumer A → Partition 0, 1
  Consumer B → Partition 2

Consumer C joins → REBALANCE (stop-the-world!):
  Consumer A → Partition 0
  Consumer B → Partition 1
  Consumer C → Partition 2

Consumer B crashes → REBALANCE:
  Consumer A → Partition 0, 1
  Consumer C → Partition 2
```

**Impact:** ALL consumers stop processing during rebalance (latency spike). Uncommitted offsets → messages reprocessed after rebalance.

### Partition Assignment Strategies
| Strategy | How | Best For |
|----------|-----|----------|
| Range | Contiguous partitions | Single topic |
| RoundRobin | Even distribution | Multiple topics |
| **Sticky** | Keep existing assignments, minimize movement | **Production** |

```yaml
spring.kafka.consumer.properties:
  partition.assignment.strategy: org.apache.kafka.clients.consumer.StickyAssignor
```

### Dead Letter Topic Pattern
```
Message fails → retry 1 → retry 2 → retry 3 → DLT (dead letter topic)

Without DLT: failed message blocks entire partition!
With DLT: failed message moved out, partition continues
```

```java
@RetryableTopic(
    attempts = "4",  // 1 original + 3 retries
    backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
    // Retries at: 1s, 2s, 4s, then DLT
    dltTopicSuffix = ".dlt",
    include = {Exception.class},
    exclude = {IllegalArgumentException.class}  // Validation errors → DLT immediately
)
@KafkaListener(topics = "order.processed")
public void process(OrderCreatedEvent event) { ... }

@DltHandler
public void handleDlt(OrderCreatedEvent event,
        @Header(KafkaHeaders.EXCEPTION_MESSAGE) String error) {
    log.error("DLT: orderId={}, error={}", event.getOrderId(), error);
    // Save to DB, alert ops, create ticket
}
```

**Retry Topics Created Automatically:**
- `order.processed`
- `order.processed-retry-0` (1s delay)
- `order.processed-retry-1` (2s delay)
- `order.processed-retry-2` (4s delay)
- `order.processed.dlt`

### Idempotent Consumer
**Problem:** At-least-once delivery → duplicates possible (crash after processing, before ack).
**Solution:** Track processed event IDs.

```
Attempt 1: Process → Success → App crashes before ack
Restart: Message redelivered → Check DB → Already processed → Skip!
```

```java
@Entity
@Table(name = "processed_events",
       indexes = @Index(name="idx_event_id", columnList="event_id", unique=true))
public class ProcessedEvent {
    @Id @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    private String eventType;
    private String topic;
    private Integer partition;
    private Long offset;
    private String correlationId;
    private LocalDateTime processedAt;
    private Long processingDurationMs;
}
```

```java
@KafkaListener(topics = "order.confirmed", groupId = "idempotent-group")
@Transactional
public void consumeIdempotent(OrderCreatedEvent event, Acknowledgment ack) {
    if (processedEventRepo.existsByEventId(event.getEventId())) {
        log.warn("DUPLICATE: eventId={} — skipping", event.getEventId());
        ack.acknowledge();
        return;
    }

    long start = System.currentTimeMillis();
    processOrderConfirmed(event);
    long duration = System.currentTimeMillis() - start;

    processedEventRepo.save(ProcessedEvent.builder()
            .eventId(event.getEventId())
            .eventType(event.getEventType())
            .processingDurationMs(duration)
            .build());

    ack.acknowledge();  // Offset commit + DB save in same @Transactional
}
```

### Custom Partitioner
```java
public class OrderPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                        Object value, byte[] valueBytes, Cluster cluster) {
        int partitions = cluster.partitionCountForTopic(topic);
        if (key == null) return (int)(System.currentTimeMillis() % partitions);
        String k = key.toString();
        if (k.contains("premium") || k.contains("vip")) return 0;  // Dedicated partition
        if (k.contains("bulk") || k.contains("wholesale")) return 1 % partitions;
        return Math.abs(k.hashCode()) % partitions;  // Hash-based default
    }
}
// Register: configProps.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, OrderPartitioner.class.getName())
```

### Batch Consumer
```java
// Factory config
factory.setBatchListener(true);  // Enable batch mode

// Consumer
@KafkaListener(topics = "inventory.reserved", groupId = "batch-group")
public void consumeBatch(List<OrderCreatedEvent> events,
        @Header(KafkaHeaders.OFFSET) List<Long> offsets,
        Acknowledgment ack) {
    log.info("Batch: {} messages", events.size());
    long start = System.currentTimeMillis();

    events.forEach(this::processSingle);

    long avg = (System.currentTimeMillis() - start) / events.size();
    log.info("Batch done: count={}, avgMs={}", events.size(), avg);
    ack.acknowledge();  // Single commit for entire batch — 5-10x faster!
}
```

**Performance:** 1000 messages, batch=100:
- Individual: 1000 ack network calls
- Batch: 10 ack network calls → 10x fewer round trips

### Offset Management
```java
@Service
public class KafkaOffsetService {
    private final KafkaListenerEndpointRegistry registry;

    // Pause all consumers (e.g., during maintenance)
    public void pauseAll() {
        registry.getAllListenerContainers().forEach(c -> { if (c.isRunning()) c.pause(); });
    }

    // Resume
    public void resumeAll() {
        registry.getAllListenerContainers().forEach(c -> { if (c.isPauseRequested()) c.resume(); });
    }

    // Status
    public Map<String, Object> getStatus() {
        return registry.getAllListenerContainers().stream()
                .collect(Collectors.toMap(
                    MessageListenerContainer::getListenerId,
                    c -> Map.of("running", c.isRunning(), "paused", c.isPauseRequested())
                ));
    }
}
```

**Manual offset reset via CLI:**
```bash
# Reset to earliest (replay all)
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group order-service-group --topic order.created \
  --reset-offsets --to-earliest --execute

# Reset to specific offset
--reset-offsets --to-offset 100 --execute

# Reset to timestamp (replay from point in time)
--reset-offsets --to-datetime 2024-10-16T10:00:00.000 --execute

# Shift backward (replay last N messages)
--reset-offsets --shift-by -100 --execute
```

### Consumer Lag Monitoring
```bash
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group order-service-group

# Output:
# GROUP               TOPIC         PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# order-service-group order.created 0          1000            1000            0
# order-service-group order.created 1          998             1000            2  ← lagging!
# order-service-group order.created 2          1001            1001            0

# LAG = LOG-END-OFFSET - CURRENT-OFFSET
# LAG > 0 → consumer behind, may need scaling
```

---

## 🛠️ Implementation Summary

### Files Added
```
kafka/partitioner/OrderPartitioner.java     — custom routing
model/ProcessedEvent.java                   — idempotency tracking
repository/ProcessedEventRepository.java
service/DeadLetterTopicService.java         — @RetryableTopic + @DltHandler
service/IdempotentKafkaConsumerService.java — duplicate detection
service/BatchKafkaConsumerService.java      — high-throughput batch
service/KafkaOffsetService.java             — pause/resume/status
controller/KafkaAdminController.java        — REST API for kafka ops
```

### KafkaAdminController Endpoints
```
GET  /api/v1/kafka/admin/offsets/{groupId}  → offset per partition
GET  /api/v1/kafka/admin/lag/{groupId}      → consumer lag
POST /api/v1/kafka/admin/pause              → pause all consumers
POST /api/v1/kafka/admin/resume             → resume all consumers
GET  /api/v1/kafka/admin/status             → listener container status
```

---

## 🧪 Testing

```bash
# Test 1: Idempotent Consumer
curl -X POST http://localhost:8080/api/v1/orders ...  # creates event
# Reset offset → message redelivered
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 --group idempotent-consumer-group \
  --topic order.confirmed --reset-offsets --to-earliest --execute
# Restart app → logs "DUPLICATE: eventId=... — skipping"

# Test 2: DLT Retry (50% failure rate in DeadLetterTopicService)
curl -X POST http://localhost:8080/api/v1/kafka/test/order-created
# Watch: attempt 1 → fail → wait 1s → attempt 2 → fail → wait 2s → ... → DLT
# Kafka UI: order.processed.dlt topic has the message

# Test 3: Batch consumer — send 50 orders
for i in {1..50}; do curl -X POST .../orders -d '...' & done; wait
# Log: "Batch: 50 messages, avgMs=11"

# Test 4: Pause/resume
curl -X POST http://localhost:8080/api/v1/kafka/admin/pause
curl -X POST .../orders  # Event published but NOT consumed (paused)
curl -X POST http://localhost:8080/api/v1/kafka/admin/resume
# Backlogged messages consumed immediately

# Test 5: Rebalance
./mvnw spring-boot:run                     # Instance 1
SERVER_PORT=8081 ./mvnw spring-boot:run    # Instance 2 → triggers rebalance!
# See: "Revoking previously assigned partitions" + "Setting newly assigned partitions"
```

---

## ✅ Completion Checklist
- [ ] OrderPartitioner with premium/bulk routing
- [ ] ProcessedEvent entity with unique index on event_id
- [ ] ProcessedEventRepository with existsByEventId
- [ ] DeadLetterTopicService with @RetryableTopic (4 attempts, exp backoff)
- [ ] @DltHandler saving to DB + alerting
- [ ] IdempotentKafkaConsumerService: check → process → save → ack
- [ ] BatchKafkaConsumerService: List<> listener
- [ ] KafkaOffsetService: pause/resume/status
- [ ] KafkaAdminController: REST management endpoints
- [ ] cleanupOldProcessedEvents @Scheduled (30-day retention)
- [ ] Retry topics created in Kafka UI
- [ ] DLT message visible after all retries exhausted
- [ ] Duplicate message skipped (idempotency working)
- [ ] Batch: single commit per batch visible in logs
- [ ] Rebalance observed when second instance starts
- [ ] Consumer lag = 0 after processing backlog

---

## 💬 Interview Q&A

**Q: What triggers a consumer group rebalance?**
A: Consumer joins, consumer crashes/heartbeat timeout, subscription changes, partition count change. During rebalance all consumers in group stop processing (stop-the-world). Sticky assignment minimizes partition movement.

**Q: What is the Dead Letter Topic pattern?**
A: Messages that fail after max retries are sent to a separate DLT topic instead of blocking the partition. DLT consumer alerts ops for manual intervention. Prevents poison pills from stopping all processing. Use @RetryableTopic for automatic DLT handling with Spring Kafka.

**Q: How do you implement idempotent consumption?**
A: Store event ID in DB after processing. Check before processing: if exists, skip and ack. Use unique constraint on event_id. Combine DB save + offset ack in same @Transactional for atomicity. Also: natural idempotency via upsert (INSERT ON CONFLICT DO NOTHING).

**Q: What is consumer lag?**
A: Difference between LOG-END-OFFSET (latest produced) and CURRENT-OFFSET (latest consumed). Lag > 0 means consumer is behind. High lag → consumer too slow, insufficient threads, or downstream service issue. Monitor with Prometheus + Grafana, alert when lag exceeds threshold.

**Q: Batch vs individual message consumption — tradeoffs?**
A: Individual: simpler, partial processing possible, one offset commit per message (many network calls). Batch: fewer commits (10x fewer), higher throughput, but entire batch reprocessed on failure. Use batch for high-volume, idempotent processing (inventory updates, analytics). Individual for critical business events.

**Q: How do you replay Kafka messages?**
A: Reset consumer group offset to earlier point using kafka-consumer-groups CLI or AdminClient API. Options: --to-earliest (all), --to-offset N (specific), --to-datetime (timestamp), --shift-by -N (N messages back). Ensure processing is idempotent before replaying!

**Q: How do you pause Kafka consumers during maintenance?**
A: Use KafkaListenerEndpointRegistry to get all MessageListenerContainers and call container.pause(). Messages accumulate in Kafka (within retention). Resume with container.resume(). Offset tracked by Kafka — consumers pick up exactly where they paused.

---

## 🔗 Next Task
**Task 12: Kafka Patterns — Event Sourcing, CQRS, Saga, Outbox** — architectural patterns that use Kafka as the backbone of distributed systems.
