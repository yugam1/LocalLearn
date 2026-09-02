# Phase 2 — Task 11: Kafka Advanced — Consumer Groups, Partitions, Offsets, DLT
**Estimated Time:** 1.5 hours | **Status:** ✅ Completed

---

## Consumer Group Rebalancing

**What:** Redistribution of partitions when group membership changes.

**Triggers:**
- Consumer joins group (new instance/scale-up)
- Consumer crashes or gracefully leaves
- Heartbeat timeout (network issue)
- Topic partition count changes
- Consumer subscription changes

**Process:**
```
Consumer C joins →
  Group coordinator notified
  ALL consumers STOP processing (stop-the-world)
  Partitions redistributed
  Consumers resume with new assignments

3 partitions + 2 consumers → 3 partitions + 3 consumers:
Before: C1:[P0,P1], C2:[P2]
After:  C1:[P0], C2:[P1], C3:[P2]
```

**Impact:** Processing gap during rebalance. Uncommitted offsets → duplicate processing on resume.

---

## Partition Assignment Strategies

| Strategy | Behavior | Problem | Use |
|---|---|---|---|
| `Range` (default) | Contiguous partitions | Imbalance with multiple topics | Avoid |
| `RoundRobin` | Even distribution | Doesn't minimize movement | OK |
| `Sticky` | Minimal partition movement | None | **Production** |

```yaml
spring:
  kafka:
    consumer:
      properties:
        partition.assignment.strategy: org.apache.kafka.clients.consumer.StickyAssignor
```

Sticky: consumers keep most of their existing partitions during rebalance → less state rebuilding → faster recovery → less duplicate processing.

---

## Offset Management Strategies

### Auto-Commit (dangerous)
```yaml
enable-auto-commit: true
auto.commit.interval.ms: 5000
```
Risk: Process message → crash before 5s commit → restart → message reprocessed as uncommitted.

### Manual Per-Message (safest, slow)
```java
processOrder(event);
ack.acknowledge(); // 1 network call per message
```

### Manual Batch Commit (production recommended)
```java
events.forEach(this::processOrder);
ack.acknowledge(); // 1 commit for entire batch → 5-10x faster
```

---

## Dead Letter Topic (DLT) Pattern

**Problem:** One bad message blocks the entire partition.

```
Normal flow:  message → process → ack → next message
Poison pill:  message → FAIL → retry → FAIL → retry → FAIL... (partition blocked!)

Solution:
message → fail → retry (backoff) → fail → retry → fail → DLT → ack → next message
                                    ^---- unblocks partition ----^
```

```java
@RetryableTopic(
    attempts = "4",          // 1 original + 3 retries
    backoff = @Backoff(
        delay = 1000,        // 1 second initial
        multiplier = 2.0,    // Exponential: 1s → 2s → 4s
        maxDelay = 10000     // Cap at 10s
    ),
    autoCreateTopics = "true",
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltTopicSuffix = ".dlt",
    include = {Exception.class},
    exclude = {IllegalArgumentException.class}  // Validation errors → DLT immediately
)
@KafkaListener(topics = "order.processed", groupId = "order-processing-group")
public void processWithRetry(
        OrderCreatedEvent event,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {

    log.info("Processing: orderId={}, topic={}", event.getOrderId(), topic);

    if (event.getOrderId() == null) {
        throw new IllegalArgumentException("orderId is null"); // → DLT immediately (no retry)
    }
    if (Math.random() < 0.5) {
        throw new RuntimeException("Simulated transient error"); // → retry with backoff
    }

    log.info("Processed successfully: orderId={}", event.getOrderId());
}

@DltHandler
public void handleDlt(
        OrderCreatedEvent event,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {

    log.error("DLT message: orderId={}, originalTopic={}, error={}",
            event.getOrderId(), topic, errorMessage);

    // Production actions:
    saveToDltDatabase(event, topic, errorMessage);  // Store for manual review
    sendOpsAlert(event, errorMessage);              // Alert on-call team
    // metrics.increment("kafka.dlt.messages")
}
```

**Retry topics auto-created:**
- `order.processed-retry-0` (1s delay)
- `order.processed-retry-1` (2s delay)
- `order.processed-retry-2` (4s delay)
- `order.processed.dlt` (permanent failure)

---

## Idempotent Consumer

**Problem:** At-least-once delivery → duplicates on consumer restart/rebalance.

```java
// entity/ProcessedEvent.java
@Entity
@Table(name = "processed_events",
    indexes = @Index(name = "idx_event_id", columnList = "event_id", unique = true))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProcessedEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true) private String eventId;
    private String eventType;
    private String topic;
    private Integer partition;
    private Long offset;
    private String correlationId;
    @CreationTimestamp private LocalDateTime processedAt;
    private Long processingDurationMs;
}

// In consumer
@KafkaListener(topics = "order.confirmed", groupId = "idempotent-group")
@Transactional
public void consumeIdempotent(OrderCreatedEvent event, Acknowledgment ack) {
    log.info("Idempotent check: eventId={}", event.getEventId());

    // Check if already processed
    if (processedEventRepo.existsByEventId(event.getEventId())) {
        log.warn("DUPLICATE DETECTED: eventId={} — skipping", event.getEventId());
        ack.acknowledge(); // Still commit to advance offset
        return;
    }

    long start = System.currentTimeMillis();
    processOrder(event); // Do the work

    // Mark as processed (atomic with transaction)
    processedEventRepo.save(ProcessedEvent.builder()
            .eventId(event.getEventId())
            .eventType(event.getEventType())
            .topic("order.confirmed")
            .correlationId(event.getCorrelationId())
            .processingDurationMs(System.currentTimeMillis() - start)
            .build());

    ack.acknowledge();
    log.info("Processed: eventId={}", event.getEventId());
}
```

**Why @Transactional here?** ProcessedEvent save + business operation are atomic. If business op fails, ProcessedEvent is NOT saved → message redelivered correctly.

---

## Batch Consumer (High Throughput)

```java
@KafkaListener(topics = "inventory.reserved", groupId = "batch-group",
               containerFactory = "kafkaListenerContainerFactory")
public void consumeBatch(
        @Payload List<OrderCreatedEvent> events,
        @Header(KafkaHeaders.RECEIVED_PARTITION) List<Integer> partitions,
        @Header(KafkaHeaders.OFFSET) List<Long> offsets,
        Acknowledgment ack) {

    log.info("Batch received: count={}, partitions={}", events.size(), partitions);
    long start = System.currentTimeMillis();

    for (int i = 0; i < events.size(); i++) {
        log.debug("Processing {}/{}: orderId={}, partition={}, offset={}",
                i + 1, events.size(), events.get(i).getOrderId(), partitions.get(i), offsets.get(i));
        processMessage(events.get(i));
    }

    ack.acknowledge(); // Single commit for entire batch!
    long ms = System.currentTimeMillis() - start;
    log.info("Batch done: count={}, totalMs={}, avgMs={}", events.size(), ms, ms / events.size());
}
```

Enable batch mode:
```yaml
spring:
  kafka:
    listener:
      type: batch
    consumer:
      max-poll-records: 100
```

**Performance:** 100 messages × 10ms each. Individual = 100 commits. Batch = 1 commit. ~5-10x faster.

---

## Custom Partitioner

```java
public class OrderPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int count = cluster.partitionCountForTopic(topic);
        if (key == null) return (int)(System.currentTimeMillis() % count);
        String k = key.toString();
        // Business routing rules:
        if (k.contains("premium")) return 0;    // Dedicated partition for premium
        if (k.contains("bulk")) return 1 % count; // Dedicated for bulk
        return Math.abs(k.hashCode()) % count;  // Default: hash-based
    }
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}

// Register in producer config:
config.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, OrderPartitioner.class.getName());
```

---

## Consumer Offset Management API

```java
@Service @RequiredArgsConstructor @Slf4j
public class KafkaOffsetService {
    private final KafkaAdmin kafkaAdmin;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    public Map<String, Long> getConsumerOffsets(String groupId)
            throws ExecutionException, InterruptedException {
        try (AdminClient ac = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    ac.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            Map<String, Long> result = new LinkedHashMap<>();
            offsets.forEach((tp, om) -> result.put(tp.topic() + "-" + tp.partition(), om.offset()));
            return result;
        }
    }

    public void pauseAllListeners() {
        listenerRegistry.getAllListenerContainers().forEach(c -> { if (c.isRunning()) c.pause(); });
        log.warn("All Kafka listeners paused");
    }

    public void resumeAllListeners() {
        listenerRegistry.getAllListenerContainers().forEach(c -> { if (c.isPauseRequested()) c.resume(); });
        log.info("All Kafka listeners resumed");
    }

    public Map<String, Object> getListenerStatus() {
        List<Map<String, Object>> containers = listenerRegistry.getAllListenerContainers()
                .stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getListenerId(),
                        "running", c.isRunning(),
                        "paused", c.isPauseRequested()))
                .collect(Collectors.toList());
        return Map.of("containers", containers, "total", containers.size());
    }
}
```

---

## Offset Reset CLI Commands

```bash
# Reset to beginning — replay all messages
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group order-service-group --topic order.created \
  --reset-offsets --to-earliest --execute

# Reset to specific offset
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group order-service-group --topic order.created:0 \
  --reset-offsets --to-offset 100 --execute

# Reset to timestamp — replay from specific time
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group order-service-group --topic order.created \
  --reset-offsets --to-datetime 2024-10-16T10:00:00.000 --execute

# Check consumer lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group order-service-group
```

---

## auto.offset.reset

| Value | Behavior | When to use |
|---|---|---|
| `earliest` | Read from partition beginning | New consumer needs historical data |
| `latest` | Read from current end | Only care about new messages |

Only applies when NO committed offset exists for the consumer group.

---

## Cleanup Old Processed Events

```java
// In scheduled task service
@Scheduled(cron = "0 0 3 * * ?") // Daily at 3 AM
@Transactional
public void cleanupOldProcessedEvents() {
    log.info("Cleaning up old processed events");
    try {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        processedEventRepo.deleteByProcessedAtBefore(cutoff);
        log.info("Cleanup done — removed events older than 30 days");
    } catch (Exception e) {
        log.error("Cleanup failed", e);
    }
}
```

---

## Interview Q&A

**Q: What triggers consumer group rebalancing?**
Consumer joins/leaves/crashes, heartbeat timeout (session.timeout.ms), partition count changes, subscription changes. During rebalance: ALL consumers stop processing (stop-the-world). Use Sticky assignor to minimize partition movement and recovery time.

**Q: Explain the Dead Letter Topic pattern.**
After N retry attempts (exponential backoff), message sent to `topic.dlt`. Offset committed → partition unblocked → normal processing continues. DLT consumer alerts ops for manual investigation. Prevents poison pills from stopping partition forever. Use @RetryableTopic with @DltHandler.

**Q: How to implement idempotent consumer?**
Track eventId in DB (unique constraint). Before processing: check if exists → skip + ack. If new: process + save eventId + ack. Wrap in @Transactional — process + save are atomic. If processing fails, eventId not saved → redelivery → retry.

**Q: Batch vs individual consumption?**
Individual: 1 commit per message (N network calls). Batch: 1 commit for N messages (1 network call). Batch 5-10x faster. Trade-off: if batch processing fails, ENTIRE batch reprocessed (at-least-once in batch mode).

**Q: Consumer lag — what is it?**
Lag = LOG_END_OFFSET - CURRENT_OFFSET (number of unprocessed messages). High lag = consumer falling behind. Alert on: lag > N messages or lag > T minutes old. Check: `kafka-consumer-groups --describe --group`.

**Q: Sticky vs RoundRobin partition assignment?**
Sticky: minimizes partition movement during rebalance (consumers keep most partitions). RoundRobin: even distribution but reassigns more partitions on membership change. Sticky is better for stateful consumers and reduces rebalance time.

**Q: Custom partitioner use cases?**
Route premium customers to dedicated partition (priority processing), geographic routing (US → partition 0, EU → partition 1), separate hot-key traffic, ensure specific message types land in specific partitions.

**Q: How to replay messages from Kafka?**
Reset consumer group offset: `--reset-offsets --to-earliest` (all), `--to-offset N` (specific), `--to-datetime` (time-based). Stop consumer group first. All committed offsets for that group are overwritten. Consumer resumes from new offset on restart.
