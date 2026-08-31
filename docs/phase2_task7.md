# Phase 2 — Task 7: Logging Strategy (SLF4J + MDC + Correlation IDs)
**Estimated Time:** 1 hour
**Status:** ✅ Completed

---

## 🎯 What You Learn
1. SLF4J as the logging facade — why not log directly to Logback
2. Log levels and when to use each in production
3. Parameterized logging for performance
4. MDC (Mapped Diagnostic Context) for contextual logging
5. Correlation IDs — tracing a request across services
6. MDCFilter — injecting context for every HTTP request
7. Logback configuration — rolling files, async appenders, JSON
8. Performance logging with AOP aspect
9. Audit logging — separate file, 365-day retention
10. Security logging — what to log and what NEVER to log

---

## 🧠 Core Concepts

### SLF4J Architecture
```
Your Code → SLF4J API → SLF4J Binding → Logback (or Log4j2)
```
Spring Boot includes Logback by default. SLF4J lets you switch implementations without changing code.

### Log Levels — Production Guide
| Level | Use When | Production? |
|-------|----------|-------------|
| TRACE | Method entry/exit, variable values | Never |
| DEBUG | Debugging: query params, intermediate values | Dev only |
| INFO | Business events: order created, payment processed | ✅ Yes |
| WARN | Recoverable issues: retry, fallback, low stock | ✅ Yes |
| ERROR | Failures requiring attention: exceptions, data loss | ✅ Yes |

```java
log.trace("Entering createOrder()");                                   // Dev only
log.debug("Building order entity for customer: {}", email);           // Dev
log.info("Order created: id={}, total={}", id, total);               // Production ✅
log.warn("Inventory low: sku={}, remaining={}", sku, remaining);     // Production ✅
log.error("Payment gateway timeout: orderId={}", orderId, e);        // Production ✅
```

### Parameterized Logging (Critical for Performance)
```java
// BAD — string always concatenated, even if DEBUG is off
log.debug("Order: " + order.getId() + " for " + order.getEmail());

// GOOD — {} substituted only if level is enabled
log.debug("Order: {} for {}", order.getId(), order.getEmail());
// Performance: 100x faster when level is off (just a boolean check)
```

### MDC — Mapped Diagnostic Context
Thread-local key-value store. Values automatically appear in log pattern.

```java
MDC.put("correlationId", "REQ-abc-123");
MDC.put("userId", "john@example.com");

log.info("Processing order");
// Output: [REQ-abc-123] [john@example.com] INFO OrderService - Processing order

MDC.clear();  // Always clear — prevents memory leaks!
```

### Correlation ID Flow
```
Client → HTTP Request (X-Correlation-ID: REQ-abc-123)
  ↓
MDCFilter sets MDC.put("correlationId", "REQ-abc-123")
  ↓
Controller → Service → Repository
  All logs: [REQ-abc-123] present
  ↓
grep "REQ-abc-123" *.log → entire request journey!
```

### MDC in Async Threads (Critical!)
```java
// PROBLEM: MDC is thread-local — async thread has empty MDC!
@Async
public void processAsync() {
    log.info("...");  // No correlationId! Different thread!
}

// SOLUTION: MDCTaskDecorator
public class MDCTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) MDC.setContextMap(context);
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
// Apply to executor: executor.setTaskDecorator(new MDCTaskDecorator());
```

### What to Log for Security (Audit Trail)
```java
// ✅ LOG THESE
log.info("Login successful: userId={}, ip={}, sessionId={}", userId, ip, sessionId);
log.warn("Login failed: email={}, ip={}, attempt={}", email, ip, attemptCount);
log.warn("Access denied: userId={}, resource={}, action={}", userId, resource, action);
log.info("Order created: orderId={}, userId={}, total={}", orderId, userId, total);
log.warn("Order deleted: orderId={}, userId={}, reason={}", orderId, userId, reason);

// ❌ NEVER LOG
log.info("Password: {}", password);       // Never!
log.info("Card: {}", cardNumber);         // Never!
log.info("Token: {}", jwtToken);          // Never!
log.info("SSN: {}", socialSecurity);      // Never!
```

---

## 🛠️ Implementation

### Logback Configuration (logback-spring.xml)
```xml
<configuration>
    <property name="LOG_PATH" value="logs"/>
    <property name="APP_NAME" value="order-service"/>

    <!-- Console: colored, readable -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%thread] [%X{correlationId:-}] [%X{userId:-}] %cyan(%logger{30}) - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- All logs: rolling by size + time -->
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

    <!-- Error only: full stack traces, 90-day retention -->
    <appender name="FILE_ERROR" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}-error.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <encoder><pattern>%d %-5level [%X{correlationId:-}] %logger - %msg%n%ex{full}</pattern></encoder>
        <rollingPolicy ...>
            <maxHistory>90</maxHistory>
        </rollingPolicy>
    </appender>

    <!-- Performance: slow queries and methods -->
    <appender name="FILE_PERFORMANCE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}-performance.log</file>
        ...
    </appender>

    <!-- Audit: security events, 365-day retention -->
    <appender name="FILE_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}-audit.log</file>
        ...
        <maxHistory>365</maxHistory>
    </appender>

    <!-- Async wrappers (non-blocking I/O) -->
    <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
        <appender-ref ref="FILE_ALL"/>
        <queueSize>512</queueSize>
        <discardingThreshold>0</discardingThreshold>
    </appender>

    <!-- Named loggers -->
    <logger name="PERFORMANCE_LOGGER" level="INFO" additivity="false">
        <appender-ref ref="FILE_PERFORMANCE"/>
    </logger>
    <logger name="AUDIT_LOGGER" level="INFO" additivity="false">
        <appender-ref ref="FILE_AUDIT"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_FILE"/>
        <appender-ref ref="FILE_ERROR"/>
    </root>

    <!-- Production profile: no console, add JSON -->
    <springProfile name="prod">
        <root level="INFO">
            <appender-ref ref="ASYNC_FILE"/>
            <appender-ref ref="FILE_ERROR"/>
        </root>
    </springProfile>
</configuration>
```

### MDCFilter
```java
@Component
@Order(1)
public class MDCFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpRes = (HttpServletResponse) res;

        try {
            // 1. Correlation ID
            String correlationId = httpReq.getHeader("X-Correlation-ID");
            if (correlationId == null) correlationId = "REQ-" + UUID.randomUUID();
            MDC.put("correlationId", correlationId);
            httpRes.setHeader("X-Correlation-ID", correlationId);

            // 2. User ID (from auth header)
            String userId = httpReq.getHeader("X-User-ID");
            if (userId != null) MDC.put("userId", userId);

            // 3. Request metadata
            MDC.put("method", httpReq.getMethod());
            MDC.put("path", httpReq.getRequestURI());
            MDC.put("ipAddress", getClientIp(httpReq));

            log.info("Incoming: method={}, path={}", httpReq.getMethod(), httpReq.getRequestURI());

            long start = System.currentTimeMillis();
            try {
                chain.doFilter(req, res);
            } finally {
                long duration = System.currentTimeMillis() - start;
                log.info("Completed: status={}, duration={}ms", httpRes.getStatus(), duration);
                if (duration > 1000) {
                    log.warn("SLOW REQUEST: path={}, duration={}ms", httpReq.getRequestURI(), duration);
                }
            }
        } finally {
            MDC.clear();  // Always clear!
        }
    }
}
```

### Performance Logging Aspect
```java
@Aspect
@Component
public class PerformanceLoggingAspect {
    private static final Logger PERF = LoggerFactory.getLogger("PERFORMANCE_LOGGER");

    @Around("execution(* com.ecommerce.orderservice.service..*(..))")
    public Object logServicePerformance(ProceedingJoinPoint jp) throws Throwable {
        String method = jp.getSignature().getDeclaringType().getSimpleName()
                       + "." + jp.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            Object result = jp.proceed();
            long ms = System.currentTimeMillis() - start;
            if (ms > 1000)
                PERF.warn("SLOW: method={}, duration={}ms", method, ms);
            else
                PERF.debug("method={}, duration={}ms", method, ms);
            return result;
        } catch (Exception e) {
            PERF.error("FAILED: method={}, duration={}ms", method, System.currentTimeMillis() - start);
            throw e;
        }
    }
}
```

### Log Pattern Variables
| MDC Key | Set By | Purpose |
|---------|--------|---------|
| correlationId | MDCFilter | Track full request across services |
| userId | MDCFilter (auth header) | Who made the request |
| method | MDCFilter | HTTP verb |
| path | MDCFilter | Request URL |
| ipAddress | MDCFilter | Client IP (proxy-aware) |
| orderId | Service layer (manual) | Context during order ops |
| executionTimeMs | MDCFilter | Request total duration |

---

## 🧪 Testing

```bash
# Request with correlation ID
curl -X POST http://localhost:8080/api/v1/orders \
  -H "X-Correlation-ID: MY-TRACE-001" \
  -H "X-User-ID: john@example.com" \
  -H "Content-Type: application/json" \
  -d '{"customerName":"John",...}'

# Console output:
# 10:30:15.123 INFO  [http-exec-1] [MY-TRACE-001] [john@example.com] MDCFilter - Incoming: method=POST, path=/api/v1/orders
# 10:30:15.145 INFO  [http-exec-1] [MY-TRACE-001] [john@example.com] OrderServiceImpl - Creating order: customer=John
# 10:30:15.350 INFO  [http-exec-1] [MY-TRACE-001] [john@example.com] OrderServiceImpl - Order created: id=1
# 10:30:15.360 INFO  [http-exec-1] [MY-TRACE-001] [john@example.com] MDCFilter - Completed: status=201, duration=237ms

# Find all logs for one request:
grep "MY-TRACE-001" logs/order-service.log

# Watch error log:
tail -f logs/order-service-error.log

# Watch audit log:
tail -f logs/order-service-audit.log
```

---

## ✅ Completion Checklist
- [ ] logback-spring.xml with console + 4 file appenders
- [ ] Rolling policy configured (size + time)
- [ ] Async appenders for FILE_ALL and FILE_ERROR
- [ ] Named loggers: PERFORMANCE_LOGGER, AUDIT_LOGGER
- [ ] logstash-logback-encoder dependency for JSON logging
- [ ] MDCFilter as @Component @Order(1)
- [ ] Correlation ID: extract from header or generate UUID
- [ ] Return X-Correlation-ID in response header
- [ ] Slow request warning (>1000ms)
- [ ] MDC always cleared in finally
- [ ] MDCTaskDecorator for async thread MDC propagation
- [ ] PerformanceLoggingAspect on service layer
- [ ] AuditLoggingService with AUDIT_LOGGER
- [ ] OrderServiceImpl using DEBUG for details, INFO for events
- [ ] Parameterized logging (no string concatenation)
- [ ] Correlation ID visible in all logs for same request

---

## 💬 Interview Q&A

**Q: What is MDC and why use it?**
A: Mapped Diagnostic Context is a thread-local map. Values set in MDC automatically appear in every log statement on that thread without explicitly passing them. Use it for correlation IDs, user IDs, tenant IDs — any context that should appear in every log line for a request. Critical for debugging in production.

**Q: How do you propagate correlation IDs across async threads?**
A: MDC is thread-local — async threads have empty MDC. Solution: TaskDecorator that captures MDC before submission and sets it in the async thread. Apply via `executor.setTaskDecorator(new MDCTaskDecorator())`. Always clear in finally to prevent leaks.

**Q: What's the difference between INFO and WARN?**
A: INFO — important business events that went as expected (order created, payment processed). WARN — unexpected situation but handled gracefully (retry attempt, low inventory, fallback used). Alerts usually trigger on ERROR; WARN is for monitoring patterns over time.

**Q: Why use parameterized logging?**
A: `log.debug("Value: " + x)` always concatenates the string. `log.debug("Value: {}", x)` only substitutes `{}` if DEBUG level is enabled. 100x performance improvement when the log level is above DEBUG. Also avoids unnecessary toString() calls.

**Q: What should go in an audit log?**
A: Who (userId, IP), what (action, resource), when (timestamp), outcome. Never include: passwords, credit cards, tokens, SSNs, sensitive PII. Audit logs should be on separate appender with long retention (365+ days) for compliance.

**Q: Why use async log appenders?**
A: Synchronous file I/O on every log call blocks the application thread — causes latency spikes. AsyncAppender writes to an in-memory queue; a background thread writes to disk. Throughput improvement: 10-100x. Trade-off: if app crashes, queued logs may be lost (mitigated by discardingThreshold=0).

**Q: How do you find all logs for a specific user request?**
A: Every request gets a unique correlation ID set in MDCFilter and included in all logs. Search: `grep "CORR-abc-123" logs/*.log`. In ELK Stack: `correlationId: "CORR-abc-123"`. Shows entire journey including async processing.

---

## 🔗 Next Task
**Task 8: Thread Pools & ExecutorService Configuration** — ThreadPoolTaskExecutor, @Async, multiple pools (email, inventory, analytics), CompletableFuture patterns, rejection policies, MDC propagation.
