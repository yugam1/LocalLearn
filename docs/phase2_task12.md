# Phase 2 — Task 12: Kafka Patterns — Event Sourcing, CQRS, Saga, Outbox
**Estimated Time:** 1 hour
**Status:** ⬜ Not Started

---

## 🎯 What You Learn
1. Event Sourcing — store events, derive state
2. CQRS — separate read/write models
3. Saga Pattern — distributed transaction coordination
4. Outbox Pattern — reliable event publishing (no dual-write problem)
5. Event versioning — evolving event schemas
6. Compensating transactions — undoing distributed work

---

## 🧠 Core Concepts

### Event Sourcing
**Traditional:** Store current state (UPDATE orders SET status='SHIPPED')
**Event Sourcing:** Store every event that happened, derive current state

```java
// Instead of:
order.setStatus("SHIPPED");
orderRepository.save(order);

// Store events:
eventStore.save(new OrderShippedEvent(orderId, trackingNumber, timestamp));

// Derive state by replaying events:
Order order = eventStore.findByOrderId(orderId)
        .stream()
        .reduce(new Order(), Order::apply);  // Fold events into state
```

**Event Store:**
```java
@Entity
@Table(name = "order_events")
public class OrderEvent {
    @Id @GeneratedValue
    private Long id;
    private Long orderId;
    private String eventType;        // "ORDER_CREATED", "ORDER_SHIPPED"
    private String eventData;        // JSON payload
    private Long eventVersion;       // Optimistic locking
    private LocalDateTime occurredAt;
}
```

**Benefits:**
- ✅ Complete audit trail (every state change recorded)
- ✅ Time travel (reconstruct state at any point in time)
- ✅ Replay events to rebuild projections
- ✅ Debugging (exactly what happened and when)

**Drawbacks:**
- ❌ More complex querying (must replay to get current state)
- ❌ Event schema evolution is hard
- ❌ Storage grows indefinitely (use snapshots)

**Snapshots:** Periodically save current state to avoid replaying all events
```java
// Every 100 events, save a snapshot
if (eventCount % 100 == 0) {
    snapshotStore.save(new OrderSnapshot(orderId, currentState, latestVersion));
}
// On load: get latest snapshot, replay only events after snapshot version
```

### CQRS — Command Query Responsibility Segregation
**Principle:** Use different models for reading and writing.

```
Write side: Command → Validate → Execute → Emit Event → Update Write DB
                                                         ↓ (via Kafka)
Read side:  Query → Read Model DB (optimized for reads, denormalized)
```

```java
// Command (write) side
@Service
public class OrderCommandService {
    public void handle(CreateOrderCommand cmd) {
        // Validate
        // Execute business logic
        Order order = createOrder(cmd);
        orderRepository.save(order);
        kafkaTemplate.send("order.created", buildEvent(order));
    }
}

// Query (read) side — separate DB, denormalized, fast reads
@Service
public class OrderQueryService {
    public OrderDetailView getOrderDetail(Long id) {
        return orderReadRepository.findDetailById(id);
        // JOIN-heavy query pre-computed into read table
    }
}

// Event consumer — keeps read model in sync
@KafkaListener(topics = "order.created")
public void onOrderCreated(OrderCreatedEvent event) {
    // Update denormalized read model
    orderReadRepository.save(OrderDetailView.from(event));
}
```

**When to Use CQRS:**
- ✅ Read and write workloads very different (99% reads)
- ✅ Complex reporting that would slow down writes
- ✅ Different scaling requirements for read vs write
- ❌ Simple CRUD apps (overkill, adds complexity)
- ❌ When eventual consistency is a problem

### Saga Pattern — Distributed Transactions
**Problem:** Transactions across multiple microservices — no single DB transaction possible.

```
Order Service → Inventory Service → Payment Service → Notification Service
      ↓ each is separate DB, no 2PC (two-phase commit)

If Payment fails: how to rollback Order + Inventory?
→ Saga: coordinated sequence of local transactions with compensating actions
```

**Choreography Saga (Event-driven):**
```
Order Service:     CREATE ORDER  → publish "order.created"
Inventory Service: reserve stock → publish "inventory.reserved"
Payment Service:   charge card   → publish "payment.processed"
Order Service:     CONFIRM ORDER (listens to payment.processed)

On failure:
Payment Service:   charge fails  → publish "payment.failed"
Inventory Service: listens       → publish "inventory.released"
Order Service:     listens       → publish "order.cancelled"
```

```java
// Order Service listens for saga events
@KafkaListener(topics = "payment.processed")
public void onPaymentProcessed(PaymentProcessedEvent event) {
    orderService.confirmOrder(event.getOrderId());
    kafkaTemplate.send("order.confirmed", new OrderConfirmedEvent(event.getOrderId()));
}

@KafkaListener(topics = "payment.failed")
public void onPaymentFailed(PaymentFailedEvent event) {
    // Compensating transaction
    orderService.cancelOrder(event.getOrderId(), "Payment failed");
    kafkaTemplate.send("order.cancelled", new OrderCancelledEvent(event.getOrderId()));
}
```

**Orchestration Saga (Central coordinator):**
```java
@Service
public class OrderSagaOrchestrator {
    public void startSaga(Long orderId) {
        sagaState.start(orderId);
        kafkaTemplate.send("inventory.reserve", new ReserveInventoryCommand(orderId));
    }

    @KafkaListener(topics = "inventory.reserved")
    public void onInventoryReserved(InventoryReservedEvent e) {
        kafkaTemplate.send("payment.charge", new ChargePaymentCommand(e.getOrderId()));
    }

    @KafkaListener(topics = "payment.failed")
    public void onPaymentFailed(PaymentFailedEvent e) {
        // Compensate: release inventory
        kafkaTemplate.send("inventory.release", new ReleaseInventoryCommand(e.getOrderId()));
        kafkaTemplate.send("order.cancelled", new OrderCancelledEvent(e.getOrderId()));
    }
}
```

### Outbox Pattern — Reliable Event Publishing
**Problem (Dual-Write):**
```
@Transactional
public void createOrder(OrderRequest req) {
    orderRepository.save(order);        // DB write ✅
    kafkaTemplate.send("order.created", event);  // Kafka write?
}
// If Kafka fails after DB commit → order in DB but event NOT published!
// If app crashes between the two writes → same problem
```

**Solution: Outbox Table**
```java
@Transactional
public void createOrder(OrderRequest req) {
    Order order = orderRepository.save(order);
    // Write event to SAME DB transaction as order
    outboxRepository.save(OutboxEvent.builder()
            .aggregateId(order.getId().toString())
            .eventType("ORDER_CREATED")
            .payload(objectMapper.writeValueAsString(buildEvent(order)))
            .status("PENDING")
            .createdAt(LocalDateTime.now())
            .build());
    // Both or neither — atomically!
}

// Separate publisher (polling or CDC)
@Scheduled(fixedDelay = 1000)
@Transactional
public void publishOutboxEvents() {
    List<OutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAt("PENDING", limit(100));
    for (OutboxEvent event : pending) {
        try {
            kafkaTemplate.send(topicFor(event.getEventType()), event.getPayload()).get();
            event.setStatus("PUBLISHED");
        } catch (Exception e) {
            event.setStatus("FAILED");
            log.error("Failed to publish outbox event: {}", event.getId(), e);
        }
        outboxRepository.save(event);
    }
}
```

**Outbox Entity:**
```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id @GeneratedValue
    private Long id;
    private String aggregateId;
    private String eventType;
    @Column(columnDefinition = "TEXT")
    private String payload;
    private String status;  // PENDING, PUBLISHED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private Integer retryCount;
}
```

### Event Versioning
```java
// V1 event
public class OrderCreatedEventV1 {
    private Long orderId;
    private String customerEmail;
}

// V2 event — added fields
public class OrderCreatedEventV2 {
    private Long orderId;
    private String customerEmail;
    private String customerName;  // new
    private String region;        // new
}

// Consumer handles both versions
@KafkaListener(topics = "order.created")
public void consume(Map<String, Object> rawEvent) {
    String version = (String) rawEvent.get("version");
    if ("2".equals(version)) {
        OrderCreatedEventV2 e = mapper.convertValue(rawEvent, OrderCreatedEventV2.class);
        processV2(e);
    } else {
        OrderCreatedEventV1 e = mapper.convertValue(rawEvent, OrderCreatedEventV1.class);
        processV1(e);
    }
}
```

---

## 🛠️ Implementation

### Outbox Entity
```java
@Entity
@Table(name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status", columnList = "status"),
        @Index(name = "idx_outbox_created", columnList = "created_at")
    })
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OutboxEvent {
    @Id @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false, length = 20)
    private String status; // PENDING, PUBLISHED, FAILED

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;
}
```

### Outbox Publisher (Polling Pattern)
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherService {
    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)  // Poll every second
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING");
        if (pending.isEmpty()) return;

        log.debug("Publishing {} outbox events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                String topic = topicForEventType(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();
                event.setStatus("PUBLISHED");
                event.setPublishedAt(LocalDateTime.now());
                log.debug("Outbox event published: id={}, type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= 5) {
                    event.setStatus("FAILED");
                    log.error("Outbox event permanently failed: id={}", event.getId(), e);
                }
                log.warn("Outbox event retry {}: id={}", event.getRetryCount(), event.getId());
            }
            outboxRepository.save(event);
        }
    }

    private String topicForEventType(String eventType) {
        return switch (eventType) {
            case "ORDER_CREATED" -> "order.created";
            case "ORDER_UPDATED" -> "order.updated";
            case "ORDER_CANCELLED" -> "order.cancelled";
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
```

### Updated OrderService Using Outbox
```java
@Transactional
public OrderResponse createOrder(OrderRequest request) {
    Order savedOrder = orderRepository.save(order);

    // Write to outbox in SAME transaction (atomic!)
    String payload = objectMapper.writeValueAsString(buildOrderCreatedEvent(savedOrder));
    outboxRepository.save(OutboxEvent.builder()
            .aggregateId(savedOrder.getId().toString())
            .eventType("ORDER_CREATED")
            .payload(payload)
            .status("PENDING")
            .build());

    return mapToResponse(savedOrder);
    // Transaction commits: order + outbox_event atomically
    // Outbox publisher picks it up and sends to Kafka
}
```

### Saga Choreography — Order Service Side
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaListener {
    private final OrderRepository orderRepository;
    private final OutboxPublisherService outboxPublisher;

    @KafkaListener(topics = "payment.processed", groupId = "order-saga-group")
    @Transactional
    public void onPaymentProcessed(PaymentProcessedEvent event, Acknowledgment ack) {
        log.info("Saga: payment processed for orderId={}", event.getOrderId());

        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // Publish next saga step via outbox
        outboxRepository.save(OutboxEvent.builder()
                .aggregateId(order.getId().toString())
                .eventType("ORDER_CONFIRMED")
                .payload(buildConfirmedPayload(order))
                .status("PENDING").build());

        ack.acknowledge();
    }

    @KafkaListener(topics = "payment.failed", groupId = "order-saga-group")
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event, Acknowledgment ack) {
        log.error("Saga: payment failed for orderId={}, compensating...", event.getOrderId());

        Order order = orderRepository.findById(event.getOrderId()).orElseThrow(...);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        // Compensating transaction: release inventory
        outboxRepository.save(OutboxEvent.builder()
                .eventType("ORDER_CANCELLED")
                .payload(buildCancelledPayload(order))
                .status("PENDING").build());

        ack.acknowledge();
    }
}
```

---

## 🧪 Testing

```bash
# Test Outbox Pattern
curl -X POST http://localhost:8080/api/v1/orders ...
# Check DB: SELECT * FROM outbox_events;
# Should see status='PENDING', then 'PUBLISHED' after scheduler runs

# Simulate Kafka down: stop Kafka container
docker stop kafka
curl -X POST .../orders  # Order saved, outbox entry created (PENDING)
# Start Kafka back: docker start kafka
# Outbox publisher retries → event published!

# Test Saga (manual steps)
# Publish payment.processed event to trigger saga:
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic payment.processed
> {"orderId":1,"paymentId":"PAY-123","amount":2999.00}
# Watch: order status changes to CONFIRMED
```

---

## ✅ Completion Checklist
- [ ] OutboxEvent entity with indexes
- [ ] OutboxEventRepository
- [ ] OutboxPublisherService with @Scheduled(fixedDelay=1000)
- [ ] OrderService updated to write to outbox instead of direct Kafka
- [ ] topicForEventType mapping
- [ ] Retry logic (5 attempts → FAILED)
- [ ] OrderSagaListener: payment.processed → confirm order
- [ ] OrderSagaListener: payment.failed → cancel order (compensating tx)
- [ ] Outbox event visible in DB before publishing
- [ ] Kafka down → orders saved → Kafka up → events published
- [ ] Saga listener responds to payment events

---

## 💬 Interview Q&A

**Q: What is the dual-write problem?**
A: Updating a database AND publishing a Kafka event are two separate operations. If the app crashes between them (or Kafka is temporarily unavailable), they go out of sync — order in DB but no Kafka event, or Kafka event but no DB record. Outbox pattern solves this by writing to the same database in the same transaction.

**Q: Explain the Outbox pattern.**
A: Write the event to an outbox table in the same DB transaction as the business data. A separate process (polling or CDC/Debezium) reads pending outbox rows and publishes to Kafka. Since both the business data and outbox row commit atomically, there's no dual-write risk. If Kafka is down, records stay in outbox until it recovers.

**Q: Choreography vs Orchestration Saga?**
A: Choreography: each service reacts to events and publishes next events. Decentralized, no single point of failure, but hard to visualize the overall flow. Orchestration: a central orchestrator tells each service what to do via commands. Easier to understand flow, but orchestrator is a coupling point. Choreography is more common in event-driven microservices.

**Q: What are compensating transactions?**
A: Since distributed sagas can't atomically rollback, you undo work by performing opposite operations. Order created → payment failed → cancel order (compensating). Inventory reserved → payment failed → release inventory (compensating). Must be idempotent since they may be retried.

**Q: What is Event Sourcing?**
A: Instead of storing current state, store every event that changed state. Current state derived by replaying events. Benefits: full audit trail, time travel (state at any point), replay to rebuild projections. Drawbacks: complex queries, storage grows. Use snapshots to avoid replaying all events on every load.

**Q: What is CQRS and when would you use it?**
A: Command Query Responsibility Segregation — separate models for reads and writes. Write model: normalized, consistent, transactional. Read model: denormalized, optimized for specific queries. Use when read workload is much larger than writes, or when read requirements (reporting) would complicate the write model. Eventual consistency between models via events.

---

## 🔗 Next Task
**Task 13: Spring Events (Local) + Kafka (Distributed)** — ApplicationEvent for in-process events, @TransactionalEventListener, combining local Spring events with Kafka for distributed communication.
