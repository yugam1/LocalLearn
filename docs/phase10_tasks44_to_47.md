# Phase 10 — Advanced Spring Features (Tasks 44–47)
**Estimated Time:** 4 hours | **Status:** ⬜ Not Started

## Task 44: Bean Scopes

| Scope | Instances | Thread-safe? | Use Case |
|---|---|---|---|
| `singleton` (default) | 1 per container | Must be | Services, Repos, Controllers |
| `prototype` | New per injection | Yes (new each time) | Stateful, non-reusable builders |
| `request` | 1 per HTTP request | Yes (1 thread) | Request-scoped context |
| `session` | 1 per HTTP session | Careful | User session data |
| `application` | 1 per ServletContext | Must be | App-wide singletons |

```java
// Prototype — new instance every injection/getBean()
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Component
public class OrderBuilder { private Order order; }

// Request-scoped — new per HTTP request
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
@Component
public class RequestContext {
    private String tenantId;
    private String correlationId;
}

// Inject request-scoped into singleton (needs proxy!)
@Service
public class OrderServiceImpl {
    private final RequestContext requestContext; // Proxied — safe in singleton
}
```

### Bean Lifecycle Hooks
```java
@Component @Slf4j
public class OrderCacheWarmup implements InitializingBean, DisposableBean {

    @PostConstruct  // After all @Autowired injected
    public void init() {
        log.info("Warming up order cache...");
        // Pre-load frequently accessed data
    }

    @Override
    public void afterPropertiesSet() { /* Same as @PostConstruct */ }

    @PreDestroy  // Before bean destroyed (app shutdown)
    public void cleanup() {
        log.info("Flushing order cache...");
    }

    @Override
    public void destroy() { /* Same as @PreDestroy */ }
}
// Lifecycle: new() → @Autowired → @PostConstruct → in use → @PreDestroy → garbage collected
```

---

## Task 45: Conditional Beans

```java
// Only create if property is set
@Bean
@ConditionalOnProperty(name = "feature.kafka.enabled", havingValue = "true", matchIfMissing = false)
public KafkaProducerService kafkaProducer() { return new KafkaProducerServiceImpl(); }

// Fallback when Kafka disabled
@Bean
@ConditionalOnMissingBean(KafkaProducerService.class)
public KafkaProducerService noOpProducer() { return new NoOpKafkaProducerService(); }

// Only if class exists on classpath
@Bean
@ConditionalOnClass(RedisOperations.class)
public RedisCacheManager redisCacheManager() { ... }

// Profile-based
@Bean
@Profile("prod")
public EmailService realEmailService() { return new SmtpEmailService(); }

@Bean
@Profile({"dev", "test"})
public EmailService mockEmailService() { return new MockEmailService(); }

// Only if another bean exists
@Bean
@ConditionalOnBean(RedisConnectionFactory.class)
public RedisCacheManager cacheManager(RedisConnectionFactory factory) { ... }

// Custom condition
@Bean
@Conditional(AwsEnvironmentCondition.class)
public S3StorageService s3Service() { ... }

public class AwsEnvironmentCondition implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata meta) {
        return "AWS".equals(ctx.getEnvironment().getProperty("deployment.environment"));
    }
}
```

---

## Task 46: Profiles & Environment Config

```yaml
# application.yml (base — always loaded)
spring:
  application:
    name: order-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}   # Default to dev if env var not set

server:
  port: 8080
  shutdown: graceful

logging:
  level:
    root: INFO
```

```yaml
# application-dev.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: orderuser
    password: orderpass
  jpa:
    show-sql: true
    hibernate:
      ddl-auto: update
  kafka:
    bootstrap-servers: localhost:9092

logging:
  level:
    com.ecommerce.orderservice: DEBUG
    org.hibernate.SQL: DEBUG

management:
  endpoints:
    web:
      exposure:
        include: "*"
```

```yaml
# application-prod.yml
spring:
  datasource:
    url: ${DATABASE_URL}                     # From environment variable
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
    hikari:
      maximum-pool-size: 20
  jpa:
    show-sql: false
    hibernate:
      ddl-auto: none                         # Flyway owns schema in prod
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS}
    producer:
      acks: all

logging:
  level:
    com.ecommerce.orderservice: INFO
    org.hibernate.SQL: WARN

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers}
  jpa:
    hibernate:
      ddl-auto: create-drop
```

### @ConfigurationProperties (Type-safe Config)
```java
@Configuration
@ConfigurationProperties(prefix = "order")
@Validated
@Data
public class OrderProperties {
    @NotNull
    private Integer maxItemsPerOrder = 100;

    @NotBlank
    private String defaultCurrency = "USD";

    @Min(1) @Max(365)
    private Integer orderRetentionDays = 90;

    private Kafka kafka = new Kafka();

    @Data
    public static class Kafka {
        private String orderCreatedTopic = "order.created";
        private Integer retryAttempts = 3;
    }
}

// application.yml
// order:
//   max-items-per-order: 50
//   default-currency: EUR
//   kafka:
//     order-created-topic: orders.v2.created

// Inject
@Service
public class OrderServiceImpl {
    private final OrderProperties orderProps;

    public OrderResponse createOrder(OrderRequest req) {
        if (req.getItems().size() > orderProps.getMaxItemsPerOrder()) {
            throw new InvalidOrderException("Too many items");
        }
    }
}
```

---

## Task 47: API Versioning Strategies

### Strategy 1: URI Path Versioning (Most Common)
```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderControllerV1 { ... }

@RestController
@RequestMapping("/api/v2/orders")
public class OrderControllerV2 { ... }
```
✅ Visible, cacheable, easy to route at gateway level
❌ URI pollution

### Strategy 2: Request Header Versioning
```java
@GetMapping(value = "/orders", headers = "API-Version=1")
public ResponseEntity<List<OrderResponseV1>> getOrdersV1() { ... }

@GetMapping(value = "/orders", headers = "API-Version=2")
public ResponseEntity<List<OrderResponseV2>> getOrdersV2() { ... }
```
✅ Clean URIs
❌ Not visible in browser, hard to test with curl

### Strategy 3: Accept Header (Content Negotiation)
```java
@GetMapping(value = "/orders", produces = "application/vnd.ecommerce.v1+json")
public ResponseEntity<List<OrderResponseV1>> getOrdersV1() { ... }

@GetMapping(value = "/orders", produces = "application/vnd.ecommerce.v2+json")
public ResponseEntity<List<OrderResponseV2>> getOrdersV2() { ... }
```
✅ RESTful standard
❌ Complex client code

### Strategy 4: Query Parameter
```java
@GetMapping(value = "/orders", params = "version=1")
public ResponseEntity<List<OrderResponseV1>> getOrdersV1() { ... }
```
❌ Caching issues, pollutes query params

### Backward Compatibility Patterns
```java
// ✅ Add optional fields (non-breaking)
@Data
public class OrderResponseV2 extends OrderResponseV1 {
    private String trackingNumber;   // New optional field
    private List<String> tags;       // New optional field
}

// ✅ Request DTO evolution
@Data
public class OrderRequestV2 extends OrderRequestV1 {
    private String promoCode;        // New optional field — ignored if null
}

// ❌ Breaking changes (require new version)
// - Removing fields
// - Changing field types
// - Making optional fields required
// - Changing enum values
```

---

## Interview Q&A

**Q: When to use prototype scope?**
When bean holds mutable state that shouldn't be shared between callers (e.g., a builder, a stateful processor). Warning: Spring creates prototype beans but doesn't manage their lifecycle after creation — @PreDestroy won't be called. Most services should be singleton.

**Q: How to inject a prototype into a singleton?**
Option 1: Inject `ApplicationContext` and call `context.getBean(MyPrototype.class)`. Option 2: Inject `ObjectProvider<MyPrototype>` and call `provider.getObject()`. Option 3: Method injection with `@Lookup`. Direct @Autowired gives SAME prototype instance (only one created).

**Q: @ConditionalOnProperty use case?**
Feature flags. `feature.new-payment.enabled: true` → inject new payment service. `false` → inject old one. No code change, just config. Also used for disabling features in test environments (e.g., disable Kafka when not needed in unit tests).

**Q: @ConfigurationProperties vs @Value?**
@Value: inject single property `@Value("${order.max-items:100}")`. @ConfigurationProperties: bind entire hierarchy to a POJO, type-safe, @Validated support, IDE completion. Use @ConfigurationProperties for groups of related config.

**Q: Which API versioning strategy do you prefer?**
URI path versioning (`/api/v1/orders`) — most practical. Visible in logs, easy to route in API gateway, easy to test, cacheable. Header versioning is cleaner but requires client setup. Pick one and be consistent across all APIs.

**Q: How do profiles work in Spring Boot?**
Active profiles loaded via `spring.profiles.active`. Spring loads `application.yml` (base) + `application-{profile}.yml` (override). Profile-specific values override base values. Multiple profiles can be active. Use `@Profile` on beans, `springProfile` in logback config.
