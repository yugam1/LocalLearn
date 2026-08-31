# Phase 2 — Task 15: Performance Optimization & Profiling
**Estimated Time:** 1 hour | **Status:** ⬜ Not Started

---

## 🎯 What You Learn
- JVM tuning basics (heap, GC selection)
- Memory leak detection (heap dumps, VisualVM)
- Thread dump analysis (deadlocks, stuck threads)
- Spring Boot performance tips
- Load testing with Gatling/k6
- Caching query results (preview of Phase 6)
- Database query analysis (EXPLAIN ANALYZE)
- Application profiling with async-profiler

---

## 🧠 Core Concepts

### JVM Tuning
```bash
# Common JVM flags for Spring Boot
java -jar order-service.jar \
  -Xms512m            # Min heap (start size)
  -Xmx2g              # Max heap
  -XX:+UseG1GC        # G1 Garbage Collector (default Java 9+)
  -XX:MaxGCPauseMillis=200    # Target max GC pause
  -XX:+HeapDumpOnOutOfMemoryError  # Auto heap dump on OOM
  -XX:HeapDumpPath=/tmp/heapdump.hprof
  -XX:+PrintGCDetails          # GC logging
  -Xlog:gc*:file=gc.log
```

### GC Selection
| GC | Best For | Characteristics |
|----|----------|----------------|
| G1GC | General purpose (default) | Low pause, balanced throughput |
| ZGC | Low latency (<1ms pause) | Java 15+, high throughput |
| Shenandoah | Concurrent collection | Low pause, medium throughput |
| ParallelGC | High throughput batch | Long pauses acceptable |

### Memory Leak Detection
```bash
# 1. Generate heap dump
jmap -dump:format=b,file=heapdump.hprof $(pgrep -f "order-service")

# Or trigger via Actuator
curl -X POST http://localhost:8080/actuator/heapdump -o heapdump.hprof

# 2. Analyze with VisualVM or Eclipse MAT
# Look for:
# - Classes with unexpectedly large retained heap
# - Collections growing unboundedly
# - Listener/observer registrations not cleaned up
```

### Common Memory Leaks in Spring
```java
// 1. MDC not cleared in async threads
@Async
public void asyncMethod() {
    MDC.put("key", "value");
    // ... forgot MDC.clear()!  → thread pool reuses thread with stale MDC
}

// 2. ThreadLocal not cleaned
ThreadLocal<Data> data = new ThreadLocal<>();
data.set(something);
// ... forgot data.remove()! → leak in thread pool

// 3. Static collections growing
private static final List<Order> cache = new ArrayList<>();
// Never evicted → grows forever

// 4. Event listener not deregistered
applicationEventPublisher.addListener(myListener);
// If myListener holds references → prevents GC
```

### Thread Dump Analysis
```bash
# Generate thread dump
jstack $(pgrep -f "order-service") > threaddump.txt
# Or: kill -3 <pid>

# Look for:
# BLOCKED — deadlock candidates
# WAITING — waiting for lock (possible deadlock)
# TIMED_WAITING — normal (sleep, wait with timeout)
# RUNNABLE — actively executing
```

### Spring Boot Performance Tips
```yaml
# 1. Turn off OSIV
spring.jpa.open-in-view: false

# 2. Use HikariCP properly
spring.datasource.hikari.maximum-pool-size: 20

# 3. Async logging (non-blocking)
# logback-spring.xml: AsyncAppender

# 4. Lazy bean initialization (faster startup)
spring.main.lazy-initialization: true  # Dev only

# 5. Class Data Sharing (faster startup)
java -Xshare:dump  # Generate shared archive
java -Xshare:on -jar app.jar
```

### Database Query Analysis
```sql
-- PostgreSQL EXPLAIN ANALYZE
EXPLAIN ANALYZE SELECT * FROM orders WHERE customer_email = 'john@example.com';

-- Output:
-- Seq Scan on orders (cost=0.00..150.00 rows=5 width=200) (actual time=0.5..15.5 rows=5 loops=1)
--   Filter: (customer_email = 'john@example.com')
-- Rows Removed by Filter: 995
-- Total runtime: 15.5ms

-- Add index!
CREATE INDEX idx_orders_customer_email ON orders(customer_email);

-- After index:
-- Index Scan using idx_orders_customer_email on orders (actual time=0.1..0.3 rows=5 loops=1)
-- Total runtime: 0.3ms  ← 50x faster!
```

### Load Testing with k6
```javascript
// k6 load test script
import http from 'k6/http';
import { check } from 'k6';

export let options = {
    vus: 100,          // 100 virtual users
    duration: '30s',   // 30 seconds
    thresholds: {
        http_req_duration: ['p95<500'],  // 95% < 500ms
        http_req_failed: ['rate<0.01'],  // <1% errors
    },
};

export default function() {
    let response = http.post('http://localhost:8080/api/v1/orders',
        JSON.stringify({
            customerName: 'Load Test User',
            customerEmail: 'load@test.com',
            items: [{ productName: 'LAPTOP-001', quantity: 1, unitPrice: 2999 }]
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    check(response, {
        'status is 201': (r) => r.status === 201,
        'response time OK': (r) => r.timings.duration < 500,
    });
}
```

```bash
# Run k6 test
k6 run load-test.js

# Output:
# http_req_duration...: avg=45ms p95=120ms
# http_reqs...........: 8430 280.1/s
# vus.................: 100
# 
# Results: ✓ All checks passed
```

### Async Profiler (CPU + Memory Profiling)
```bash
# Attach to running JVM
./asprof -d 30 -f profile.html $(pgrep -f "order-service")

# Or with spring-boot:run via agent
java -agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=profile.html -jar app.jar

# Opens flame graph in browser
# Shows which methods consume most CPU
```

### Performance Metrics to Monitor
```java
// Custom Micrometer timers (preview of Task 25)
@Autowired
private MeterRegistry meterRegistry;

public OrderResponse createOrder(OrderRequest request) {
    return Timer.builder("order.creation.time")
            .description("Time to create an order")
            .tag("status", "success")
            .register(meterRegistry)
            .record(() -> {
                // actual order creation
                return orderService.createOrder(request);
            });
}
```

---

## 🛠️ Implementation

### Performance Test Controller (Keep from Task 6)
```java
// Already implemented in Task 6
// GET /api/v1/performance-test/n-plus-1
// GET /api/v1/performance-test/join-fetch
// GET /api/v1/performance-test/compare-all
```

### application.yml Performance Settings
```yaml
spring:
  jpa:
    properties:
      hibernate:
        generate_statistics: true     # Monitor query counts
        jdbc:
          batch_size: 20              # Batch inserts
        order_inserts: true
        order_updates: true
    open-in-view: false               # CRITICAL

server:
  tomcat:
    threads:
      max: 200          # Max HTTP threads
      min-spare: 20     # Min idle threads
    max-connections: 8192
    accept-count: 100   # Queue for connections when all threads busy

logging:
  level:
    org.hibernate.stat: INFO  # See query statistics in logs
```

---

## 🧪 Testing

```bash
# Baseline — measure before optimization
k6 run --vus 10 --duration 30s load-test.js
# Results: avg=450ms, p95=980ms

# Apply optimizations:
# 1. Add indexes
# 2. Fix N+1 queries
# 3. Enable batch fetching
# 4. Tune HikariCP

# After optimization
k6 run --vus 10 --duration 30s load-test.js
# Results: avg=45ms, p95=120ms  ← 10x improvement!

# Profile hot spots
./asprof -d 30 -f profile.html $(pgrep -f "order-service")
# Open profile.html → identify slow methods
```

---

## ✅ Completion Checklist
- [ ] JVM flags documented for production startup
- [ ] Heap dump generation tested (Actuator endpoint)
- [ ] Thread dump generated and analyzed
- [ ] EXPLAIN ANALYZE run on slow queries
- [ ] Index added for customer_email column
- [ ] k6 (or similar) load test written
- [ ] Load test run: 100 VUs for 30s
- [ ] p95 < 500ms achieved
- [ ] Tomcat thread pool configured in application.yml

---

## 💬 Interview Q&A

**Q: How do you diagnose a slow Spring Boot application?**
A: Methodically: 1) Check Actuator /actuator/metrics for active threads, DB pool usage. 2) Enable Hibernate statistics — high query count = N+1. 3) EXPLAIN ANALYZE slow queries — missing indexes? 4) Thread dump — deadlocks or thread starvation? 5) Heap dump + MAT — memory leak? 6) async-profiler flame graph — CPU hotspot?

**Q: What is a heap dump and how do you analyze it?**
A: Snapshot of JVM heap at a point in time. Contains all objects, their sizes, references. Analyze with Eclipse Memory Analyzer (MAT) or VisualVM. Look for: Leak Suspects (large retained memory), dominator tree (which objects hold most memory), histograms (most instances). Generated with jmap or -XX:+HeapDumpOnOutOfMemoryError.

**Q: What JVM GC would you choose for a Spring Boot web service?**
A: G1GC (default Java 9+) for general purpose — good balance of throughput and pause times, handles large heaps well. ZGC (Java 15+) for ultra-low latency requirements (<1ms GC pauses). Avoid SerialGC and ParallelGC for web services (long STW pauses).

**Q: How do you identify N+1 queries in production?**
A: Enable Hibernate statistics (generate_statistics=true). Monitor collection fetch count vs load count — high fetch count relative to load count = N+1. Or enable slow query logging in PostgreSQL. Or use APM tools (New Relic, Datadog) which show SQL distribution.

---

## 🔗 Next Phase
**Phase 3: Testing** — Unit testing with Mockito, integration testing with @SpringBootTest, @DataJpaTest, @WebMvcTest, TestContainers for Kafka and PostgreSQL, contract testing.
