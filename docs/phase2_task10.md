# Phase 2 — Task 10: Apache Kafka Basics — Producer & Consumer
**Estimated Time:** 1.5 hours
**Status:** ✅ Completed

---

## 🎯 What You Learn
1. Kafka vs Node.js EventEmitter — distributed vs in-process
2. Topics, Partitions, Brokers, Consumer Groups — the full picture
3. KafkaTemplate for publishing (async with callback)
4. @KafkaListener for consuming
5. JSON serialization / deserialization
6. Manual offset acknowledgment
7. Producer acks — reliability vs performance tradeoff
8. MDC propagation to Kafka consumers
9. Multiple consumer groups consuming same topic
10. Event DTO design with BaseEvent hierarchy

---

## 🧠 Core Concepts

### Kafka vs Node.js EventEmitter
| Feature | Node.js EventEmitter | Apache Kafka |
|---------|---------------------|-------------|
| Scope | Single process | Distributed cluster |
| Persistence | In-memory only | Disk (configurable retention) |
| Replay | Impossible | Yes — reset offset |
| Multiple consumers | Same handlers receive | Different consumer groups |
| Scalability | Single machine | Horizontal |
| Ordering | Sequential | Per-partition |
| Durability | Lost on crash | Survives crashes |

### Core Architecture
```
Producers → Broker (Kafka) → Consumers

Broker contains Topics
Topics contain Partitions
Partitions contain ordered messages with offsets

Topic: order.created (3 partitions)
Partition 0: [msg0] [msg3] [msg6]  offset: 0, 1, 2
Partition 1: [msg1] [msg4] [msg7]  offset: 0, 1, 2
Partition 2: [msg2] [msg5] [msg8]  offset: 0, 1, 2

Consumer Group: order-service-group
Consumer A → Partition 0
Consumer B → Partition 1
Consumer C → Partition 2
(each partition consumed by exactly ONE consumer per group)
```

### Producer Acks
| acks | Wait For | Risk | Performance |
|------|----------|------|-------------|
| `0` | Nothing | Data loss possible | Fastest |
| `1` | Leader write | Lost if leader crashes before replication | Fast |
| `all` | Leader + all replicas | No data loss | Slowest |

**Production:** Use `acks=all` for critical events (orders, payments). `acks=1` for most cases.

### Consumer Offset
```
Partition 0: [msg0] [msg1] [msg2] [msg3] [msg4]
                                   ↑
                            committed offset=3
                    (msgs 0,1,2 processed, 3 is next to read)

auto-commit (default): offset committed every 5s regardless of processing
manual commit: explicit acknowledgment after successful processing (production!)
```

### Key/Partition Routing
```java
// No key → round-robin across partitions
kafkaTemplate.send("orders", event);

// With key → hash-based, same key → same partition → ordered
kafkaTemplate.send("orders", orderId.toString(), event);
// All events for order #123 always go to same partition → ordered!
```

---

## 🛠️ Implementation

### Docker Setup (docker-compose-kafka.yml)
```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports: ["2181:2181"]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  kafka-ui:  # Visualization at http://localhost:8090
    image: provectuslabs/kafka-ui:latest
    ports: ["8090:8080"]
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
```

### application.yml — Kafka Section
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092

    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      enable-idempotence: true
      batch-size: 16384
      linger-ms: 10
      compression-type: snappy
      properties:
        spring.json.add.type.headers: false

    consumer:
      group-id: order-service-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 50
      properties:
        spring.json.trusted.packages: com.ecommerce.orderservice.event

    listener:
      ack-mode: manual
      concurrency: 3
```

### Event Hierarchy
```java
// Base event with common metadata
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public abstract class BaseEvent {
    private String eventId;       // UUID — for idempotency
    private String eventType;     // "ORDER_CREATED"
    private LocalDateTime eventTimestamp;
    private String correlationId; // from MDC
    private String userId;
}

// Concrete events
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode(callSuper=true)
public class OrderCreatedEvent extends BaseEvent {
    private Long orderId;
    private String orderNumber;
    private String customerName;
    private String customerEmail;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private List<OrderItemEvent> items;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemEvent {
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
```

### Topic Config
```java
@Configuration
public class KafkaTopicConfig {
    public static final String ORDER_CREATED_TOPIC = "order.created";
    public static final String ORDER_UPDATED_TOPIC = "order.updated";
    public static final String ORDER_CANCELLED_TOPIC = "order.cancelled";

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC)
                .partitions(3)  // 3 = 3 consumers can process in parallel
                .replicas(1)    // 3 replicas in production
                .config("retention.ms", "604800000")  // 7 days
                .config("compression.type", "snappy")
                .build();
    }
}
```

### KafkaProducerService
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishOrderCreated(OrderCreatedEvent event) {
        String key = event.getOrderId().toString();  // Same order → same partition
        kafkaTemplate.send(ORDER_CREATED_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Published ORDER_CREATED: orderId={}, partition={}, offset={}",
                                event.getOrderId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to publish ORDER_CREATED: orderId={}", event.getOrderId(), ex);
                    }
                });
    }

    // Synchronous send — use when you need confirmation before proceeding
    public void publishOrderCreatedSync(OrderCreatedEvent event) throws Exception {
        SendResult<String, Object> result = kafkaTemplate.send(ORDER_CREATED_TOPIC,
                event.getOrderId().toString(), event).get();
        log.info("Published (sync): partition={}, offset={}",
                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
    }
}
```

### KafkaConsumerService
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    @KafkaListener(
        topics = ORDER_CREATED_TOPIC,
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        // Propagate correlation ID
        if (event.getCorrelationId() != null)
            MDC.put("correlationId", event.getCorrelationId());

        log.info("Consuming ORDER_CREATED: orderId={}, partition={}, offset={}, thread={}",
                event.getOrderId(), partition, offset, Thread.currentThread().getName());

        try {
            processOrderCreatedEvent(event);
            ack.acknowledge();  // Manual commit — only after successful processing
            log.info("ORDER_CREATED processed: orderId={}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed ORDER_CREATED: orderId={}", event.getOrderId(), e);
            // Don't ack → message redelivered
        } finally {
            MDC.clear();
        }
    }

    // Different consumer group — same messages, independent offset
    @KafkaListener(
        topics = {ORDER_CREATED_TOPIC, ORDER_UPDATED_TOPIC},
        groupId = "analytics-group"
    )
    public void consumeForAnalytics(
            @Payload Object event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {
        log.info("Analytics: topic={}, type={}", topic, event.getClass().getSimpleName());
        ack.acknowledge();
    }
}
```

### OrderService Integration
```java
@Transactional
public OrderResponse createOrder(OrderRequest request) {
    // ... build and save order ...
    Order savedOrder = orderRepository.save(order);

    // Reserve inventory (same transaction)
    inventoryService.reserveStock(...);

    // Audit (REQUIRES_NEW)
    auditService.logOrderCreated(savedOrder.getId(), email);

    // Publish Kafka event (after commit — use TransactionalEventListener for strict ordering)
    OrderCreatedEvent event = buildOrderCreatedEvent(savedOrder, MDC.get("correlationId"));
    kafkaProducerService.publishOrderCreated(event);

    return mapToResponse(savedOrder);
}

private OrderCreatedEvent buildOrderCreatedEvent(Order order, String correlationId) {
    return OrderCreatedEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("ORDER_CREATED")
            .eventTimestamp(LocalDateTime.now())
            .correlationId(correlationId)
            .userId(order.getCustomerEmail())
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .customerName(order.getCustomerName())
            .customerEmail(order.getCustomerEmail())
            .totalAmount(order.getTotalAmount())
            .status(order.getStatus())
            .items(order.getItems().stream().map(i ->
                    new OrderCreatedEvent.OrderItemEvent(i.getProductName(), i.getQuantity(), i.getUnitPrice()))
                    .collect(Collectors.toList()))
            .build();
}
```

---

## 🧪 Testing

```bash
# Start Kafka
docker-compose -f docker-compose-kafka.yml up -d

# Start app
./mvnw spring-boot:run

# Create order → Kafka event published + consumed
curl -X POST http://localhost:8080/api/v1/orders \
  -H "X-Correlation-ID: TEST-001" \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John","customerEmail":"john@x.com","items":[{"productName":"LAPTOP-001","quantity":1,"unitPrice":2999}]}'

# Console — producer:
# [TEST-001] KafkaProducerService - Published ORDER_CREATED: orderId=1, partition=0, offset=0

# Console — consumer (different thread!):
# [scheduled-1] [TEST-001] KafkaConsumerService - Consuming ORDER_CREATED: orderId=1, partition=0, offset=0
# CORRELATION ID PROPAGATED via MDCTaskDecorator!

# Kafka UI: http://localhost:8090 → Topics → order.created → Messages
# See full JSON event with eventId, correlationId, orderId, items...

# Check consumer group status
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe --group order-service-group
# GROUP  TOPIC         PARTITION  CURRENT-OFFSET  LAG
# ...    order.created 0          1               0    (lag=0 means all processed!)
```

---

## ✅ Completion Checklist
- [ ] Kafka + Zookeeper + Kafka UI running via docker-compose
- [ ] spring-kafka dependency in pom.xml
- [ ] Kafka config in application.yml (producer + consumer + listener)
- [ ] BaseEvent abstract class with common metadata
- [ ] OrderCreatedEvent, OrderUpdatedEvent, OrderCancelledEvent
- [ ] KafkaProducerConfig with all production settings
- [ ] KafkaConsumerConfig with ErrorHandlingDeserializer
- [ ] KafkaTopicConfig with 3-partition topics
- [ ] KafkaProducerService: async publish with callback
- [ ] KafkaConsumerService: @KafkaListener with manual ack
- [ ] MDC correlationId propagated to consumer thread
- [ ] OrderService.createOrder() publishes event
- [ ] OrderService.updateOrder() publishes event
- [ ] OrderService.deleteOrder() publishes event
- [ ] Topics visible in Kafka UI
- [ ] Messages visible in Kafka UI with correct JSON
- [ ] Consumer lag = 0 after processing
- [ ] Correlation ID appears in consumer logs

---

## 💬 Interview Q&A

**Q: What is Kafka and how does it differ from a traditional message queue (RabbitMQ)?**
A: Kafka is a distributed event streaming platform. Unlike RabbitMQ: messages are persistent (disk, configurable retention), consumers can replay from any offset, multiple consumer groups get their own copy of every message, partitioning enables horizontal parallelism, designed for high-throughput (millions/second). RabbitMQ messages deleted after consumption, push-based, lower throughput.

**Q: Explain topics, partitions, and consumer groups.**
A: Topic = logical channel (like event name). Partition = physical division for parallelism — messages ordered within partition, distributed across partitions. Consumer group = set of consumers sharing load. Each partition consumed by exactly ONE consumer per group. 3 partitions + 3 consumers = perfect parallelism.

**Q: Auto-commit vs manual commit — which for production?**
A: Manual. Auto-commit periodically commits offsets regardless of processing outcome. If app crashes after commit but before actual processing — message lost. Manual commit via ack.acknowledge() only after successful processing ensures at-least-once delivery.

**Q: What does acks=all mean?**
A: Producer waits for acknowledgment from leader AND all in-sync replicas before considering message sent. Guarantees no data loss even if leader crashes immediately after write. Slower than acks=1 (leader only) or acks=0 (fire-and-forget).

**Q: How do you use message keys in Kafka?**
A: Messages with same key always go to same partition. Ensures ordering for related messages. Use orderId as key → all events for same order are ordered within partition. Without key → round-robin → events for same entity may end up in different partitions → ordering lost.

**Q: What is enable.idempotence on the producer?**
A: Prevents producer from sending duplicate messages when retrying. Internally uses sequence numbers per partition. Combined with acks=all and retries > 0, gives exactly-once producer semantics. Should always be enabled in production.

**Q: How do you trace a request through Kafka?**
A: Include correlationId (from MDC) in event payload as BaseEvent field. Consumer extracts and puts in its MDC thread. Grep logs by correlationId shows full journey: HTTP request → order created → Kafka published → Kafka consumed → downstream processing.

---

## 🔗 Next Task
**Task 11: Kafka Advanced** — consumer groups rebalancing, custom partitioner, DLT, @RetryableTopic, idempotent consumers, batch processing, offset management, consumer lag monitoring.
