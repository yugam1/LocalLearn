# Phase 5 — Observability (Tasks 24–27)
**Estimated Time:** 5 hours | **Status:** ⬜ Partially Done (Task 7 = Logging ✅)

---

## Task 24: Spring Boot Actuator + Custom Health Checks

### Expose Endpoints
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,loggers,env,beans
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized   # 'always' in dev, 'when-authorized' in prod
      show-components: always
    info:
      enabled: true
  info:
    git:
      mode: full
    build:
      enabled: true
```

### Custom Health Indicator
```java
@Component
public class KafkaHealthIndicator implements HealthIndicator {
    private final KafkaAdmin kafkaAdmin;

    @Override
    public Health health() {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            client.describeCluster().nodes().get(5, TimeUnit.SECONDS);
            return Health.up()
                    .withDetail("kafka", "Connected")
                    .withDetail("bootstrapServers", "localhost:9092")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("kafka", "Disconnected")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}

@Component
public class DatabaseHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.prepareStatement("SELECT 1").executeQuery()) {
            if (rs.next()) {
                return Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("status", "Connected")
                        .build();
            }
        } catch (SQLException e) {
            return Health.down()
                    .withDetail("database", "PostgreSQL")
                    .withDetail("error", e.getMessage())
                    .build();
        }
        return Health.unknown().build();
    }
}
```

### Custom Info Contributor
```java
@Component
public class AppInfoContributor implements InfoContributor {
    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("app", Map.of(
            "name", "Order Service",
            "version", "1.0.0",
            "environment", "production",
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime() + "ms"
        ));
    }
}
```

### Kubernetes Probes
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```
- `/actuator/health/liveness` → K8s liveness probe (is app alive?)
- `/actuator/health/readiness` → K8s readiness probe (ready for traffic?)

---

## Task 25: Micrometer + Prometheus + Grafana

### Setup
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true   # Latency histograms
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
    tags:
      application: order-service
      environment: production
```

### Custom Metrics
```java
@Service @RequiredArgsConstructor @Slf4j
public class OrderServiceImpl implements OrderService {
    private final MeterRegistry meterRegistry;
    private final Counter orderCreatedCounter;
    private final Counter orderFailedCounter;
    private final Timer orderProcessingTimer;
    private final AtomicInteger activeOrderGauge;

    public OrderServiceImpl(MeterRegistry meterRegistry, ...) {
        this.orderCreatedCounter = Counter.builder("orders.created.total")
                .tag("service", "order-service")
                .description("Total orders created")
                .register(meterRegistry);

        this.orderFailedCounter = Counter.builder("orders.failed.total")
                .tag("service", "order-service")
                .description("Total order creation failures")
                .register(meterRegistry);

        this.orderProcessingTimer = Timer.builder("orders.processing.duration")
                .description("Order processing time")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.activeOrderGauge = meterRegistry.gauge("orders.active", new AtomicInteger(0));
    }

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest req) {
        return orderProcessingTimer.record(() -> {
            try {
                Order saved = orderRepository.save(buildOrder(req));
                orderCreatedCounter.increment();
                activeOrderGauge.incrementAndGet();
                kafkaProducer.publishOrderCreated(buildEvent(saved));
                return mapToResponse(saved);
            } catch (Exception e) {
                orderFailedCounter.increment();
                throw e;
            }
        });
    }
}
```

### Prometheus Scrape Config
```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['order-service:8080']
```

### Grafana Dashboards (Key Metrics to Graph)
```
HTTP:
- http_server_requests_seconds_count (RPS by endpoint)
- http_server_requests_seconds_p99 (latency 99th percentile)

JVM:
- jvm_memory_used_bytes (heap usage)
- jvm_gc_pause_seconds_count (GC frequency)
- jvm_threads_live (thread count)

HikariCP:
- hikaricp_connections_active (active DB connections)
- hikaricp_connections_pending (waiting for connection)
- hikaricp_connections_timeout_total (connection timeouts)

Kafka:
- kafka_consumer_fetch_manager_records_lag_max (consumer lag)

Custom:
- orders_created_total (order volume)
- orders_processing_duration_seconds_p99 (order latency)
- orders_active (current in-flight orders)
```

---

## Task 26: Distributed Tracing (Zipkin/Jaeger)

### Setup (Micrometer Tracing)
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 1.0 = 100% (use 0.1 in prod = 10%)
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### Start Zipkin
```bash
docker run -d -p 9411:9411 openzipkin/zipkin
# UI: http://localhost:9411
```

### What Gets Traced Automatically
- All HTTP requests (servlet filters)
- Spring Data JPA queries
- Kafka producer/consumer operations
- `@Async` method calls
- RestTemplate / WebClient calls

### Manual Tracing
```java
@Service @RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private final Tracer tracer;

    public OrderResponse createOrder(OrderRequest req) {
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("order.customer", req.getCustomerEmail());
            span.tag("order.itemCount", String.valueOf(req.getItems().size()));
        }
        // ... business logic
    }
}
```

### Trace Propagation Headers
- `X-B3-TraceId` — unique trace ID (correlates all spans in a request)
- `X-B3-SpanId` — current operation span
- `X-B3-ParentSpanId` — caller span

Micrometer injects these automatically into:
- HTTP client calls (RestTemplate, WebClient, Feign)
- Kafka producer messages (as headers)
- Kafka consumer reads (extracted from headers)

---

## Interview Q&A

**Q: What does Spring Boot Actuator provide?**
Production-ready monitoring endpoints: `/health` (component health), `/metrics` (JVM + custom metrics), `/env` (configuration), `/loggers` (change log levels at runtime), `/prometheus` (Prometheus scrape format), `/info` (app metadata). Configurable which are exposed.

**Q: Custom vs built-in health indicators?**
Built-in: DataSource, Kafka, Redis, Elasticsearch, disk space — auto-configured. Custom: implement `HealthIndicator` interface, return `Health.up()/.down()/.unknown()` with details. Spring aggregates all indicators into overall health status.

**Q: What is Micrometer?**
Instrumentation facade for metrics — like SLF4J but for metrics. Write metrics code once against Micrometer API, publish to any backend (Prometheus, Datadog, New Relic, InfluxDB) by adding the right registry dependency. Spring Boot auto-configures JVM, HTTP, DB, HikariCP metrics.

**Q: Counter vs Gauge vs Timer?**
Counter: monotonically increasing number (total requests, errors). Gauge: point-in-time value that can go up/down (active connections, queue size). Timer: latency distribution (request duration, DB query time). Use Timer for durations, Counter for events, Gauge for current state.

**Q: What is distributed tracing?**
Tracking a request as it flows through multiple microservices. Each service adds span data (operation name, duration, tags). Spans linked by TraceId. Visualization (Zipkin/Jaeger) shows waterfall diagram of full request lifecycle. Critical for debugging latency in distributed systems.

**Q: Sampling rate for tracing in production?**
Not 100% — too much overhead and storage. Typical: 1-10% (sampling.probability: 0.01-0.1). Head-based sampling: decide at trace start. Tail-based sampling: decide after trace completes (sample slow/error traces). High-value operations (payments) can be sampled at higher rate.

**Q: What metrics should alert on?**
Error rate: `rate(http_requests_total{status=~"5.*"}[5m]) > 0.01` (>1% error rate). Latency: `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 2` (p99 >2s). Queue size: `hikaricp_connections_pending > 5`. Consumer lag: `kafka_consumer_fetch_manager_records_lag_max > 1000`.
