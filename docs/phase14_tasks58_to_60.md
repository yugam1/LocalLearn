# Phase 14 — Production Hardening (Tasks 58–60)
**Estimated Time:** 3 hours | **Status:** ⬜ Not Started

## Task 58: Feature Flags

### Why Feature Flags?
- Deploy code without activating feature (dark launch)
- Gradual rollout (canary without K8s complexity)
- A/B testing
- Instant rollback without redeployment
- Environment-specific features (only in prod for specific customers)

### Simple Implementation (Property-Based)
```java
@Configuration
@ConfigurationProperties(prefix = "features")
@Data
public class FeatureFlags {
    private boolean newCheckoutEnabled = false;
    private boolean kafkaPublishEnabled = true;
    private boolean redisRateLimitEnabled = false;
    private int maxOrderItemsPerRequest = 100;
}

@Service @RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final FeatureFlags features;

    public OrderResponse createOrder(OrderRequest req) {
        if (req.getItems().size() > features.getMaxOrderItemsPerRequest()) {
            throw new InvalidOrderException("Too many items");
        }
        // ... create order ...
        if (features.isKafkaPublishEnabled()) {
            kafkaProducer.publishOrderCreated(event);
        }
        return response;
    }
}
```

```yaml
# application.yml
features:
  new-checkout-enabled: false
  kafka-publish-enabled: true
  redis-rate-limit-enabled: false
  max-order-items-per-request: 100

# application-prod.yml (override for prod)
features:
  new-checkout-enabled: true
  max-order-items-per-request: 50
```

### Runtime Toggle (No Restart) — @RefreshScope
```java
@RestController
@RefreshScope  // Re-reads config on POST /actuator/refresh
public class OrderController {
    @Value("${features.new-checkout-enabled:false}")
    private boolean newCheckoutEnabled;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest req) {
        if (newCheckoutEnabled) {
            return ResponseEntity.ok(newCheckoutService.createOrder(req));
        }
        return ResponseEntity.ok(legacyCheckoutService.createOrder(req));
    }
}
```

```bash
# After updating config in Config Server, trigger refresh
curl -X POST http://localhost:8080/actuator/refresh
```

### Database-Backed Feature Flags (Most Flexible)
```java
@Entity @Table(name = "feature_flags")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FeatureFlag {
    @Id private String name;
    private boolean enabled;
    private String description;
    private String enabledForUsers;  // Comma-separated user IDs
    private LocalDateTime updatedAt;
}

@Service @RequiredArgsConstructor @Slf4j
public class FeatureFlagService {
    private final FeatureFlagRepository repository;

    @Cacheable(value = "featureFlags", key = "#flagName")
    public boolean isEnabled(String flagName) {
        return repository.findById(flagName)
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    @CacheEvict(value = "featureFlags", key = "#flagName")
    public void toggle(String flagName, boolean enabled) {
        FeatureFlag flag = repository.findById(flagName)
                .orElse(FeatureFlag.builder().name(flagName).build());
        flag.setEnabled(enabled);
        flag.setUpdatedAt(LocalDateTime.now());
        repository.save(flag);
        log.warn("Feature flag toggled: name={}, enabled={}", flagName, enabled);
    }
}

// Usage
if (featureFlagService.isEnabled("new-payment-gateway")) {
    return newPaymentGateway.process(payment);
} else {
    return legacyPaymentGateway.process(payment);
}
```

---

## Task 59: Blue-Green & Canary Deployments

### Blue-Green with Kubernetes
```yaml
# Blue deployment (current production)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service-blue
  labels:
    app: order-service
    slot: blue
spec:
  replicas: 3
  selector:
    matchLabels:
      app: order-service
      slot: blue
  template:
    metadata:
      labels:
        app: order-service
        slot: blue
    spec:
      containers:
      - name: order-service
        image: order-service:1.0.0

---
# Green deployment (new version)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service-green
  labels:
    slot: green
spec:
  replicas: 3
  selector:
    matchLabels:
      app: order-service
      slot: green
  template:
    metadata:
      labels:
        app: order-service
        slot: green
    spec:
      containers:
      - name: order-service
        image: order-service:2.0.0

---
# Service — switch by changing selector
apiVersion: v1
kind: Service
metadata:
  name: order-service
spec:
  selector:
    app: order-service
    slot: blue    # ← Change to 'green' to switch traffic!
  ports:
  - port: 80
    targetPort: 8080
```

```bash
# Deploy green
kubectl apply -f k8s/deployment-green.yml

# Test green directly (via green-specific service)
kubectl expose deployment order-service-green --name=order-service-green --port=80

# Switch traffic to green
kubectl patch service order-service -p '{"spec":{"selector":{"slot":"green"}}}'

# Monitor for errors
kubectl logs -l slot=green -n production --follow

# Rollback instantly if issues
kubectl patch service order-service -p '{"spec":{"selector":{"slot":"blue"}}}'

# After confidence, scale down blue
kubectl scale deployment order-service-blue --replicas=0
```

### Canary with Nginx Ingress
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: order-service-canary
  annotations:
    nginx.ingress.kubernetes.io/canary: "true"
    nginx.ingress.kubernetes.io/canary-weight: "10"  # 10% to canary
spec:
  rules:
  - host: api.example.com
    http:
      paths:
      - path: /api/v1/orders
        pathType: Prefix
        backend:
          service:
            name: order-service-v2
            port:
              number: 80
```

---

## Task 60: Graceful Shutdown & Zero-Downtime

### Spring Boot Graceful Shutdown Config
```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # Max wait for in-flight requests
```

### What Happens on SIGTERM
```
1. K8s sends SIGTERM (pod terminating)
2. K8s stops routing new traffic to pod (readiness probe fails)
3. Spring: stop accepting new HTTP requests
4. Spring: wait for active requests to complete (up to 30s)
5. Kafka consumers: stop polling, commit current offsets
6. Scheduler: wait for active scheduled tasks
7. HikariCP: wait for active DB queries, close pool
8. Spring context: destroy all beans
9. JVM exits (exit code 0)
```

### Custom Shutdown Hooks
```java
@Component @RequiredArgsConstructor @Slf4j
public class GracefulShutdownHooks implements DisposableBean {
    private final KafkaListenerEndpointRegistry kafkaRegistry;
    private final ScheduledTaskHolder scheduledTaskHolder;

    @Override
    public void destroy() throws Exception {
        log.info("=== Graceful Shutdown Initiated ===");

        // 1. Stop Kafka consumers (commit offsets)
        log.info("Stopping Kafka consumers...");
        kafkaRegistry.getAllListenerContainers().forEach(container -> {
            container.stop(() -> log.info("Kafka consumer stopped: {}", container.getListenerId()));
        });

        // 2. Cancel pending scheduled tasks
        log.info("Cancelling scheduled tasks...");
        scheduledTaskHolder.getScheduledTasks().forEach(task -> task.cancel());

        log.info("=== Graceful Shutdown Complete ===");
    }
}

// Kubernetes terminationGracePeriodSeconds must be >= Spring timeout
// deployment.yml: terminationGracePeriodSeconds: 60  (30s Spring + 30s buffer)
```

### Zero-Downtime Database Migrations
```
Rule: Migrations must be backward compatible with OLD code running simultaneously

Phase 1 (deploy migration first):
  V10__add_new_column.sql:
    ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(100);
  ← Old code runs fine (ignores new column)
  ← New code reads/writes new column

Phase 2 (deploy new code):
  ← Both old and new code work with current schema

Phase 3 (cleanup after all instances updated):
  V11__make_column_not_null.sql:
    ALTER TABLE orders ALTER COLUMN tracking_number SET NOT NULL;
  ← Only after all instances run new code
```

```
NEVER:
  ❌ Drop a column (old code will fail to read it)
  ❌ Rename a column (breaks old code)
  ❌ Change column type incompatibly
  ❌ Add NOT NULL without default (old code can't insert)

SAFE:
  ✅ Add nullable column
  ✅ Add column with default value
  ✅ Add new table
  ✅ Add index (use CONCURRENTLY in PostgreSQL)
  ✅ Expand enum values (not remove)
```

```sql
-- Safe: Add index without locking table
CREATE INDEX CONCURRENTLY idx_orders_new_column ON orders(tracking_number);
-- No table lock! (Only in PostgreSQL)
```

---

## Interview Q&A

**Q: What are feature flags and why use them?**
Feature flags decouple deployment from release. Code deployed to production but feature inactive. Can activate for specific users (beta testers), percentage of users (canary), or environments. Instant rollback without redeployment — just toggle flag. Enables trunk-based development (no long-lived branches).

**Q: Blue-Green deployment mechanics?**
Two identical production environments (Blue=current, Green=new). Deploy new version to Green, run tests. Switch traffic (load balancer/K8s Service selector) from Blue to Green. Instant rollback = switch back. Cost: doubles infrastructure during transition. No rolling update complexity.

**Q: Canary vs Blue-Green?**
Blue-Green: all-or-nothing switch. Canary: gradual traffic shift (5%→25%→50%→100%). Canary: smaller blast radius, requires traffic splitting infra (Ingress/Istio/ALB), gradual monitoring. Blue-Green: simpler, instant rollback, higher cost. Canary better for risk averse, Blue-Green for simpler systems.

**Q: What is graceful shutdown and why does it matter?**
On SIGTERM (K8s pod termination), app: stops accepting new requests, finishes in-flight requests (up to timeout), commits Kafka offsets, closes DB connections cleanly. Without graceful shutdown: active HTTP connections dropped (client errors), Kafka offsets not committed (reprocessing on restart), DB connections leaked.

**Q: K8s terminationGracePeriodSeconds must be > Spring timeout — why?**
K8s sends SIGTERM, waits `terminationGracePeriodSeconds` (default 30s), then SIGKILL (force kill). Spring's `timeout-per-shutdown-phase` must be less than K8s timeout. If Spring needs 30s to shutdown, set K8s to 60s (30s Spring + 30s buffer). SIGKILL bypasses Spring hooks = dirty shutdown.

**Q: Zero-downtime migration — how to rename a column?**
Can't rename directly (breaks running code). Process: (1) Add new column, (2) Deploy code writing to BOTH columns, (3) Backfill old data to new column, (4) Deploy code reading from new column only, (5) Remove old column. Takes 3 deployments but truly zero downtime.
