# Phase 1 — Task 7: Logging — SLF4J + MDC + Correlation IDs
**Estimated Time:** 1 hour | **Status:** ✅ Completed

---

## Log Levels

| Level | Use | Prod? |
|---|---|---|
| TRACE | Every method entry/variable | ❌ Never |
| DEBUG | Intermediate values | ❌ Rarely |
| INFO | Business events (order created) | ✅ Yes |
| WARN | Recoverable issues (retry, low stock) | ✅ Yes |
| ERROR | Failures needing attention | ✅ Yes |

```java
// ALWAYS parameterized — never concatenate
log.info("Order created: orderId={}, total={}", order.getId(), order.getTotalAmount());
log.error("Payment failed: orderId={}", orderId, exception); // exception last = stack trace
```

---

## MDC (Mapped Diagnostic Context)

Thread-local map auto-included in every log line.

```java
MDC.put("correlationId", "REQ-abc-123");
MDC.put("userId", "john@example.com");
log.info("Processing");  // → [REQ-abc-123] [john@example.com] Processing
MDC.clear();             // ALWAYS clear — prevents leak into next request
```

### MDC Filter

```java
@Component @Order(1) @Slf4j
public class MDCFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpRes = (HttpServletResponse) res;
        try {
            String correlationId = httpReq.getHeader("X-Correlation-ID");
            if (correlationId == null) correlationId = "REQ-" + UUID.randomUUID();
            MDC.put("correlationId", correlationId);
            httpRes.setHeader("X-Correlation-ID", correlationId);
            String userId = httpReq.getHeader("X-User-ID");
            if (userId != null) MDC.put("userId", userId);
            MDC.put("ipAddress", getClientIp(httpReq));
            long start = System.currentTimeMillis();
            log.info("→ {} {}", httpReq.getMethod(), httpReq.getRequestURI());
            try {
                chain.doFilter(req, res);
            } finally {
                long ms = System.currentTimeMillis() - start;
                log.info("← {} {} {}ms [{}]", httpReq.getMethod(), httpReq.getRequestURI(), ms, httpRes.getStatus());
                if (ms > 1000) log.warn("SLOW REQUEST: {}ms on {} {}", ms, httpReq.getMethod(), httpReq.getRequestURI());
            }
        } finally {
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = req.getRemoteAddr();
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }
}
```

### MDC in Async Threads

```java
// Problem: @Async threads start with EMPTY MDC
// Solution: MDCTaskDecorator
public class MDCTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap(); // Capture
        return () -> {
            try {
                if (contextMap != null) MDC.setContextMap(contextMap); // Restore
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
// Apply: executor.setTaskDecorator(new MDCTaskDecorator())
```

---

## Logback Configuration (logback-spring.xml)

```xml
<configuration>
  <property name="LOG_PATH" value="logs"/>
  <property name="APP_NAME" value="order-service"/>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss} %highlight(%-5level) [%thread] [%X{correlationId:-}] [%X{userId:-}] %cyan(%logger{30}) - %msg%n</pattern>
    </encoder>
  </appender>

  <appender name="FILE_ALL" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_PATH}/${APP_NAME}.log</file>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{correlationId:-}] [%X{userId:-}] %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>${LOG_PATH}/${APP_NAME}-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>30</maxHistory>
      <totalSizeCap>3GB</totalSizeCap>
    </rollingPolicy>
  </appender>

  <appender name="FILE_ERROR" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_PATH}/${APP_NAME}-error.log</file>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>ERROR</level></filter>
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%X{correlationId:-}] %logger{36} - %msg%n%ex{full}</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>${LOG_PATH}/${APP_NAME}-error-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>90</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Async — don't block app threads on disk I/O -->
  <appender name="ASYNC_ALL" class="ch.qos.logback.classic.AsyncAppender">
    <appender-ref ref="FILE_ALL"/>
    <queueSize>512</queueSize>
    <discardingThreshold>0</discardingThreshold>
  </appender>

  <logger name="PERFORMANCE_LOGGER" level="INFO" additivity="false">
    <appender-ref ref="FILE_ALL"/><appender-ref ref="CONSOLE"/>
  </logger>
  <logger name="AUDIT_LOGGER" level="INFO" additivity="false">
    <appender-ref ref="FILE_ALL"/>
  </logger>

  <logger name="com.ecommerce.orderservice" level="DEBUG"/>
  <logger name="org.hibernate.SQL" level="DEBUG"/>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
    <appender-ref ref="ASYNC_ALL"/>
    <appender-ref ref="FILE_ERROR"/>
  </root>

  <springProfile name="prod">
    <logger name="com.ecommerce.orderservice" level="INFO"/>
    <logger name="org.hibernate.SQL" level="WARN"/>
    <root level="INFO">
      <appender-ref ref="ASYNC_ALL"/>
      <appender-ref ref="FILE_ERROR"/>
    </root>
  </springProfile>
</configuration>
```

---

## Performance Logging Aspect

```java
@Aspect @Component @Slf4j
public class PerformanceLoggingAspect {
    private static final Logger PERF = LoggerFactory.getLogger("PERFORMANCE_LOGGER");

    @Around("execution(* com.ecommerce.orderservice.service..*(..))")
    public Object logService(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long ms = System.currentTimeMillis() - start;
            if (ms > 1000) PERF.warn("SLOW: method={}, duration={}ms", method, ms);
            else PERF.debug("OK: method={}, duration={}ms", method, ms);
            return result;
        } catch (Exception e) {
            PERF.error("FAILED: method={}, duration={}ms", method, System.currentTimeMillis() - start);
            throw e;
        }
    }
}
```

---

## What NOT to Log

```java
// NEVER
log.info("password={}", password);          // ❌
log.info("card={}", creditCardNumber);       // ❌
log.info("token={}", jwtToken);             // ❌

// Sanitized versions
log.info("email={}", maskEmail(email));      // j***n@example.com ✅
log.info("card last4={}", card.substring(card.length()-4)); // 4417 ✅
```

---

## Correlation ID Cross-Service Flow

```
Client → MDCFilter generates "REQ-abc-123"
Order Service logs:    [REQ-abc-123] "Creating order"
Inventory Service:     [REQ-abc-123] "Checking stock"   ← passed as HTTP header
Kafka Consumer:        [REQ-abc-123] "Event consumed"   ← passed in event payload

grep "REQ-abc-123" *.log  → Full distributed trace!
```

```java
// Include in Kafka event
event.setCorrelationId(MDC.get("correlationId"));

// Restore in consumer
if (event.getCorrelationId() != null) MDC.put("correlationId", event.getCorrelationId());
```

---

## Interview Q&A

**Q: MDC and why use it?**
Thread-local map auto-included in every log line. Enables finding all logs for one request by searching correlationId. Critical for microservices — trace across services without manually passing log context.

**Q: MDC with async threads?**
MDC is thread-local — async threads start empty. Implement TaskDecorator: capture `MDC.getCopyOfContextMap()` before submission, restore with `MDC.setContextMap()` in async thread, clear in finally. Apply: `executor.setTaskDecorator(new MDCTaskDecorator())`.

**Q: Parameterized logging vs concatenation?**
Concatenation always executes string building even if level is OFF. Parameterized form evaluates only when level active. 100x faster for disabled levels in production.

**Q: Why async appenders?**
Synchronous file I/O blocks app thread on every log write. Async appender uses in-memory queue — app thread enqueues and continues. Background thread drains to disk. Prevents logging latency from affecting request response times.

**Q: What is a correlation ID?**
Unique ID per request, propagated through all downstream calls via HTTP headers, Kafka event payloads, and MDC. Generated in MDCFilter. Enables: `grep "REQ-abc-123"` → complete request trace across all services.

**Q: INFO vs WARN vs ERROR?**
INFO: expected business events (order created, payment succeeded). WARN: unexpected but handled (retry attempt, low inventory, deprecated API call). ERROR: failure needing attention (exception thrown, payment gateway down). Alerts trigger on ERROR. WARN reviewed periodically.
