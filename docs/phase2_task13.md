# Phase 2 — Task 13: Spring Events + @TransactionalEventListener
**Estimated Time:** 1 hour | **Status:** ⬜ Not Started

---

## 🎯 What You Learn
- ApplicationEvent and ApplicationEventPublisher — in-process events
- @EventListener — synchronous event handling
- @Async on @EventListener — async handling
- @TransactionalEventListener — publish AFTER transaction commits (fixes dual-write!)
- TransactionPhase options: AFTER_COMMIT, AFTER_ROLLBACK, AFTER_COMPLETION, BEFORE_COMMIT
- Combining Spring Events + Kafka (Spring Events for local, Kafka for distributed)
- Custom application events with rich payloads

---

## 🧠 Core Concepts

### Spring Events vs Kafka
| | Spring Events | Apache Kafka |
|--|--------------|-------------|
| Scope | Same JVM process | Distributed cluster |
| Persistence | None (in-memory) | Yes |
| Reliable delivery | No | Yes |
| When to use | Decoupling within service | Cross-service communication |

### Basic Event Publishing
```java
// 1. Define event
public class OrderCreatedApplicationEvent extends ApplicationEvent {
    private final Order order;
    private final String correlationId;

    public OrderCreatedApplicationEvent(Object source, Order order, String correlationId) {
        super(source);
        this.order = order;
        this.correlationId = correlationId;
    }
}

// 2. Publish
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        Order savedOrder = orderRepository.save(order);
        // Publish event (synchronous by default — runs in same thread, same transaction)
        eventPublisher.publishEvent(new OrderCreatedApplicationEvent(this, savedOrder, MDC.get("correlationId")));
        return mapToResponse(savedOrder);
    }
}

// 3. Listen
@Component
@Slf4j
public class OrderEventListener {

    @EventListener  // Synchronous — runs in caller's thread and transaction
    public void onOrderCreated(OrderCreatedApplicationEvent event) {
        log.info("Order created event received: orderId={}", event.getOrder().getId());
    }
}
```

### @TransactionalEventListener — The Key Pattern
**Problem without it:**
```java
@EventListener
public void onOrderCreated(OrderCreatedApplicationEvent event) {
    kafkaTemplate.send("order.created", buildKafkaEvent(event.getOrder()));
    // Runs DURING transaction — if transaction rolls back AFTER this,
    // Kafka event already sent but order not in DB! Inconsistency!
}
```

**Fix:**
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderCreated(OrderCreatedApplicationEvent event) {
    kafkaTemplate.send("order.created", buildKafkaEvent(event.getOrder()));
    // Only runs AFTER transaction successfully commits!
    // If transaction rolls back → this method never called
}
```

### TransactionPhase Options
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)      // Default — most common
@TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)    // Cleanup on failure
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)  // Always — commit or rollback
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)     // Just before commit
```

### Async Event Handling
```java
@Component
@Slf4j
public class OrderEventListener {

    @Async("emailExecutor")  // Runs in email thread pool
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendConfirmationEmail(OrderCreatedApplicationEvent event) {
        log.info("Sending email async: orderId={}", event.getOrder().getId());
        emailService.sendOrderConfirmation(event.getOrder());
    }

    @Async("analyticsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateAnalytics(OrderCreatedApplicationEvent event) {
        analyticsService.recordOrderCreated(event.getOrder());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onOrderCreationFailed(OrderCreatedApplicationEvent event) {
        log.error("Order creation failed and rolled back: orderId attempt for {}",
                event.getOrder().getCustomerEmail());
        // Alert ops, cleanup, etc.
    }
}
```

### Architecture: Spring Events + Kafka Together
```
HTTP Request
     ↓
OrderService.createOrder()
     ↓ @Transactional begins
  - Build order
  - Save to DB
  - eventPublisher.publishEvent(OrderCreatedApplicationEvent)
     ↓ @Transactional COMMITS
  All @TransactionalEventListener(AFTER_COMMIT) fire:
     ↓
  KafkaPublisherListener:
    - Build Kafka event
    - kafkaTemplate.send("order.created", event)  ← Kafka publish AFTER DB commit
  EmailListener (async):
    - Send confirmation email
  AnalyticsListener (async):
    - Update dashboard
```

---

## 🛠️ Implementation

### Custom Events
```java
// Base application event
public abstract class BaseApplicationEvent extends ApplicationEvent {
    private final String correlationId;
    private final String userId;

    protected BaseApplicationEvent(Object source, String correlationId, String userId) {
        super(source);
        this.correlationId = correlationId;
        this.userId = userId;
    }
}

// Order events
public class OrderCreatedApplicationEvent extends BaseApplicationEvent {
    private final Order order;
    public OrderCreatedApplicationEvent(Object source, Order order) {
        super(source, MDC.get("correlationId"), MDC.get("userId"));
        this.order = order;
    }
}

public class OrderCancelledApplicationEvent extends BaseApplicationEvent {
    private final Order order;
    private final String reason;
}
```

### Updated OrderService — Event Publishing
```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        Order savedOrder = orderRepository.save(buildOrder(request));
        log.info("Order saved: orderId={}", savedOrder.getId());

        // Publish Spring event — Kafka send happens in @TransactionalEventListener AFTER_COMMIT
        eventPublisher.publishEvent(new OrderCreatedApplicationEvent(this, savedOrder));

        return mapToResponse(savedOrder);
        // If we return here normally → tx commits → listeners fire
        // If exception thrown → tx rollbacks → listeners DON'T fire (AFTER_COMMIT)
    }

    @Transactional
    public void deleteOrder(Long id) {
        Order order = orderRepository.findByIdWithItems(id).orElseThrow(...);
        orderRepository.delete(order);
        eventPublisher.publishEvent(new OrderCancelledApplicationEvent(this, order, "Deleted by user"));
    }
}
```

### Listener Class
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderApplicationEventListener {
    private final KafkaProducerService kafkaProducerService;
    private final EmailService emailService;
    private final AuditLoggingService auditLoggingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishToKafka(OrderCreatedApplicationEvent event) {
        log.info("AFTER_COMMIT: Publishing to Kafka: orderId={}", event.getOrder().getId());
        // MDC context needs to be re-set (new thread context after commit)
        MDC.put("correlationId", event.getCorrelationId());
        try {
            OrderCreatedEvent kafkaEvent = buildKafkaEvent(event.getOrder(), event.getCorrelationId());
            kafkaProducerService.publishOrderCreated(kafkaEvent);
        } finally {
            MDC.clear();
        }
    }

    @Async("emailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendConfirmationEmail(OrderCreatedApplicationEvent event) {
        log.info("Sending confirmation email: orderId={}", event.getOrder().getId());
        emailService.sendOrderConfirmation(event.getOrder());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onOrderCreationFailed(OrderCreatedApplicationEvent event) {
        log.error("Order creation ROLLED BACK for customer: {}", event.getOrder().getCustomerEmail());
        // Can't send Kafka event (order never made it to DB)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void auditOrderCreated(OrderCreatedApplicationEvent event) {
        auditLoggingService.logOrderCreated(
                event.getOrder().getId(),
                event.getOrder().getOrderNumber(),
                event.getOrder().getCustomerEmail(),
                event.getOrder().getTotalAmount().toString()
        );
    }
}
```

---

## 🧪 Testing

```bash
# Create order — watch AFTER_COMMIT firing
curl -X POST http://localhost:8080/api/v1/orders \
  -H "X-Correlation-ID: SPRING-EVENT-001" \
  -d '{"customerName":"Alice","customerEmail":"alice@x.com","items":[...]}'

# Console:
# [http-exec-1] OrderServiceImpl - Order saved: orderId=1
# [http-exec-1] ORDER TRANSACTIONAL — TX COMMITS
# [http-exec-1] OrderApplicationEventListener - AFTER_COMMIT: Publishing to Kafka
# [async-email-1] OrderApplicationEventListener - Sending confirmation email
# Kafka event visible in order.created topic

# Test rollback — create order with invalid product (stock=0 if constraint set)
# Console:
# OrderServiceImpl - Order saved: orderId=2
# [http-exec-1] InsufficientStockException thrown — TX ROLLBACKS
# [http-exec-1] OrderApplicationEventListener - Order creation ROLLED BACK for...
# NO Kafka event published! (AFTER_COMMIT never fired because tx rolled back)
```

---

## ✅ Completion Checklist
- [ ] BaseApplicationEvent with correlationId, userId
- [ ] OrderCreatedApplicationEvent, OrderCancelledApplicationEvent
- [ ] ApplicationEventPublisher injected in OrderService
- [ ] eventPublisher.publishEvent() called in createOrder, deleteOrder
- [ ] OrderApplicationEventListener with @TransactionalEventListener
- [ ] AFTER_COMMIT listener: publishToKafka
- [ ] AFTER_COMMIT async listener: sendConfirmationEmail
- [ ] AFTER_ROLLBACK listener: log failure
- [ ] Test: Kafka event published only after successful commit
- [ ] Test: Kafka event NOT published when tx rolls back

---

## 💬 Interview Q&A

**Q: What is @TransactionalEventListener and why is it important?**
A: Standard @EventListener fires during the transaction (before commit). If you publish to Kafka inside it and the transaction later rolls back, you have a Kafka event for a non-existent DB record. @TransactionalEventListener(AFTER_COMMIT) fires only after the transaction successfully commits — solving the dual-write consistency problem without a full Outbox implementation.

**Q: When would you use Spring Events vs Kafka?**
A: Spring Events for in-process communication within the same microservice (decoupling components, triggering side effects). Kafka for inter-service communication (other services need to know). Often combine: Spring Events published during transaction, @TransactionalEventListener(AFTER_COMMIT) then sends to Kafka.

**Q: What happens to event listeners if the transaction rolls back?**
A: AFTER_COMMIT listeners are NOT called. AFTER_ROLLBACK listeners ARE called. This is the key benefit — you can safely send to external systems in AFTER_COMMIT knowing the DB write succeeded.

---

## 🔗 Next Task
**Task 14: Reactive Programming with Spring WebFlux** — Mono/Flux, non-blocking APIs, R2DBC reactive database access, when to use reactive vs traditional blocking.
