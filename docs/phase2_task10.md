# Phase 2 — Task 10: Apache Kafka — Producer & Consumer Basics
**Estimated Time:** 1.5 hours | **Status:** ✅ Completed

---

## Kafka vs Node.js EventEmitter

| Feature | Node.js EventEmitter | Apache Kafka |
|---|---|---|
| Scope | Single process | Distributed across servers |
| Persistence | In-memory — lost on crash | Persistent on disk |
| Replay | Impossible | Yes — reset offset |
| Multiple consumers | Same handler runs | Independent consumer groups |
| Scale | Single machine | Horizontal scaling |
| Order guarantee | Within process | Within partition |

---

## Core Architecture

**Topic** — Logical channel. e.g., `order.created`, `inventory.updated`

**Partition** — Physical division of topic. Messages ordered within partition. Multiple partitions = parallelism.
```
order.created (3 partitions):
Partition 0: [msg1] [msg5] [msg9]   ← ordered
Partition 1: [msg2] [msg6]
Partition 2: [msg3] [msg7] [msg10]
Cross-partition order NOT guaranteed
```

**Consumer Group** — Consumers sharing work. Each partition → exactly 1 consumer in group.
```
3 partitions, 3 consumers in "order-service-group":
Consumer A → Partition 0  ↗ parallel processing
Consumer B → Partition 1  →
Consumer C → Partition 2  ↘
```

**Offset** — Current position in partition. Committed after processing. Enable replay (reset to any offset).

**Producer Acknowledgment:**
```
acks=0: fire-and-forget — fastest, can lose messages
acks=1: leader confirms — balanced (default)
acks=all: all replicas confirm — safest, use for orders/payments
```

---

## Docker Setup

```yaml
# docker-compose-kafka.yml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports: ["2181:2181"]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on: [zookeeper]
    ports: ["9092:9092", "29092:29092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports: ["8090:8080"]
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092
```

```bash
docker-compose -f docker-compose-kafka.yml up -d
# Kafka UI: http://localhost:8090
```

---

## application.yml Kafka Config

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all                      # Safest — wait for all replicas
      retries: 3
      enable-idempotence: true       # Prevents duplicate messages on retry
      batch-size: 16384              # 16KB batch
      linger-ms: 10                  # Wait 10ms to accumulate batch
      compression-type: snappy
      properties:
        spring.json.add.type.headers: false
    consumer:
      group-id: order-service-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      auto-offset-reset: earliest    # Start from beginning if no offset
      enable-auto-commit: false      # Manual commit for reliability
      max-poll-records: 50
      properties:
        spring.json.trusted.packages: com.ecommerce.orderservice.event
    listener:
      ack-mode: manual               # Explicit acknowledge after processing
      concurrency: 3                 # 3 consumer threads per listener
```

---

## Event Classes

```java
// event/BaseEvent.java
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
public abstract class BaseEvent {
    private String eventId;           // UUID — idempotency key
    private String eventType;
    private LocalDateTime eventTimestamp;
    private String correlationId;     // Distributed tracing
    private String userId;
}

// event/OrderCreatedEvent.java
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
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

// event/OrderUpdatedEvent.java
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderUpdatedEvent extends BaseEvent {
    private Long orderId;
    private String orderNumber;
    private OrderStatus oldStatus;
    private OrderStatus newStatus;
    private BigDecimal oldTotalAmount;
    private BigDecimal newTotalAmount;
    private String updateReason;
}

// event/OrderCancelledEvent.java
@Data @SuperBuilder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderCancelledEvent extends BaseEvent {
    private Long orderId;
    private String orderNumber;
    private String customerEmail;
    private BigDecimal refundAmount;
    private String cancellationReason;
}
```

---

## Topic Configuration

```java
@Configuration
@Slf4j
public class KafkaTopicConfig {
    public static final String ORDER_CREATED_TOPIC = "order.created";
    public static final String ORDER_UPDATED_TOPIC = "order.updated";
    public static final String ORDER_CANCELLED_TOPIC = "order.cancelled";
    public static final String INVENTORY_RESERVED_TOPIC = "inventory.reserved";
    public static final String NOTIFICATION_TOPIC = "notification.email";

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", "604800000")  // 7 days
                .config("compression.type", "snappy")
                .build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(ORDER_CANCELLED_TOPIC)
                .partitions(3).replicas(1)
                .config("retention.ms", "2592000000") // 30 days (refund processing)
                .build();
    }
}
```

---

## Producer Configuration

```java
@Configuration @Slf4j
public class KafkaProducerConfig {
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

---

## Producer Service

```java
@Service @RequiredArgsConstructor @Slf4j
public class KafkaProducerService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Async with callback (recommended)
    public void publishOrderCreated(OrderCreatedEvent event) {
        String key = event.getOrderId().toString(); // Same orderId → same partition → ordered
        log.info("Publishing: orderId={}, topic={}", event.getOrderId(), ORDER_CREATED_TOPIC);

        kafkaTemplate.send(ORDER_CREATED_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("Published: orderId={}, partition={}, offset={}",
                            event.getOrderId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to publish: orderId={}", event.getOrderId(), ex);
                    }
                });
    }

    // Synchronous (use for critical events where confirmation needed)
    public void publishOrderCreatedSync(OrderCreatedEvent event) {
        try {
            SendResult<String, Object> result =
                kafkaTemplate.send(ORDER_CREATED_TOPIC, event.getOrderId().toString(), event).get();
            log.info("Published sync: partition={}, offset={}",
                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (Exception e) {
            log.error("Failed sync publish: orderId={}", event.getOrderId(), e);
            throw new RuntimeException("Failed to publish event", e);
        }
    }
}
```

---

## Consumer Configuration

```java
@Configuration @Slf4j
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}") private String bootstrapServers;
    @Value("${spring.kafka.consumer.group-id}") private String groupId;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ecommerce.orderservice.event");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "com.ecommerce.orderservice.event.OrderCreatedEvent");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3); // 3 threads per listener
        factory.setCommonErrorHandler(new DefaultErrorHandler());
        return factory;
    }
}
```

---

## Consumer Service

```java
@Service @RequiredArgsConstructor @Slf4j
public class KafkaConsumerService {

    @KafkaListener(topics = ORDER_CREATED_TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consumeOrderCreated(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {

        // Propagate correlation ID
        if (event.getCorrelationId() != null) MDC.put("correlationId", event.getCorrelationId());

        log.info("Consuming: orderId={}, partition={}, offset={}, thread={}",
                event.getOrderId(), partition, offset, Thread.currentThread().getName());

        try {
            processOrderCreated(event);
            ack.acknowledge(); // Manual commit — only after successful processing
            log.info("Processed and committed: orderId={}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed — message will be redelivered: orderId={}", event.getOrderId(), e);
            // Don't ack — message redelivered from last committed offset
        } finally {
            MDC.clear();
        }
    }

    // Different consumer group — receives SAME messages independently
    @KafkaListener(topics = {ORDER_CREATED_TOPIC, ORDER_UPDATED_TOPIC}, groupId = "analytics-group")
    public void consumeForAnalytics(
            @Payload Object event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {
        log.info("Analytics: topic={}, type={}", topic, event.getClass().getSimpleName());
        ack.acknowledge();
    }

    private void processOrderCreated(OrderCreatedEvent event) {
        log.info("Processing order: orderId={}, total={}", event.getOrderId(), event.getTotalAmount());
        // Send notification, update search index, trigger fulfillment, etc.
    }
}
```

---

## Integrate Kafka into Order Service

```java
// In OrderServiceImpl.createOrder() — after saving order
private OrderCreatedEvent buildOrderCreatedEvent(Order order) {
    List<OrderCreatedEvent.OrderItemEvent> itemEvents = order.getItems().stream()
            .map(i -> new OrderCreatedEvent.OrderItemEvent(i.getProductName(), i.getQuantity(), i.getUnitPrice()))
            .collect(Collectors.toList());

    return OrderCreatedEvent.builder()
            .eventId(UUID.randomUUID().toString())     // Unique ID for idempotency
            .eventType("ORDER_CREATED")
            .eventTimestamp(LocalDateTime.now())
            .correlationId(MDC.get("correlationId"))   // Distributed tracing
            .userId(order.getCustomerEmail())
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .customerName(order.getCustomerName())
            .customerEmail(order.getCustomerEmail())
            .totalAmount(order.getTotalAmount())
            .status(order.getStatus())
            .items(itemEvents)
            .build();
}
```

---

## Interview Q&A

**Q: Kafka vs RabbitMQ?**
Kafka: persistent (disk), pull-based, partitioned, high-throughput, replay, messages retained after consumption. RabbitMQ: push-based, simpler routing, message deleted after ack. Kafka for event streaming/high volume. RabbitMQ for traditional task queues.

**Q: How is message ordering guaranteed?**
Within a partition — guaranteed. Across partitions — NOT guaranteed. Use message key: same key → same partition → ordered. Example: orderId as key → all events for same order always in same partition, always ordered.

**Q: What is a consumer group?**
Multiple consumers sharing work on a topic. Each partition consumed by exactly 1 consumer in the group. Add consumers up to partition count for higher throughput. Different groups consume independently — same messages for different purposes.

**Q: Auto-commit vs manual commit?**
Auto-commit: offset committed periodically (5s default) regardless of processing outcome. Risk: commit before processing complete → crash → message lost. Manual: call `ack.acknowledge()` only after successful processing → at-least-once delivery guarantee.

**Q: What does enable-idempotence do on producer?**
Prevents duplicate messages during retries. Producer assigns sequence numbers. Broker rejects duplicate with same sequence from same producer. Must be combined with `acks=all` for full effect.

**Q: What is acks=all?**
Producer waits for acknowledgment from ALL in-sync replicas (leader + followers). Safest — no data loss even if leader crashes immediately after write. Slowest. Use for critical events (orders, payments).

**Q: What happens if consumer crashes without committing offset?**
After restart, consumer resumes from LAST committed offset — reprocesses messages from that point. This is why idempotent processing is important (same message processed twice should have same result).
