# Phase 9 — AOP (Tasks 42–43)
**Estimated Time:** 2 hours | **Status:** ⬜ Not Started

## Core Concepts
```
Aspect   = Class containing cross-cutting logic (logging, timing, security)
Pointcut = Expression defining WHICH methods to intercept
Advice   = WHAT to execute (Before, After, Around, AfterReturning, AfterThrowing)
JoinPoint= A specific method execution being intercepted
```

## Enable AOP
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

## Pointcut Expressions
```java
// All methods in service package (and sub-packages)
execution(* com.ecommerce.orderservice.service..*(..))

// All public methods in any class
execution(public * *(..))

// Methods annotated with @Transactional
@annotation(org.springframework.transaction.annotation.Transactional)

// All methods in a specific class
within(com.ecommerce.orderservice.service.impl.OrderServiceImpl)

// Methods with specific return type
execution(com.ecommerce.orderservice.dto.response.OrderResponse com.ecommerce..*(..))

// Combine with && || !
execution(* com.ecommerce.service..*(..)) && !execution(* com.ecommerce.service..get*(..))
```

## Advice Types
```java
@Aspect @Component @Slf4j
public class ExampleAspect {

    @Before("execution(* com.ecommerce.service..*(..))")
    public void before(JoinPoint jp) {
        log.debug("Before: {}.{}",
            jp.getSignature().getDeclaringType().getSimpleName(),
            jp.getSignature().getName());
    }

    @After("execution(* com.ecommerce.service..*(..))")
    public void after(JoinPoint jp) { /* Always runs — success or exception */ }

    @AfterReturning(pointcut = "execution(* com.ecommerce.service..*(..))", returning = "result")
    public void afterSuccess(JoinPoint jp, Object result) { /* Runs only on success */ }

    @AfterThrowing(pointcut = "execution(* com.ecommerce.service..*(..))", throwing = "ex")
    public void afterError(JoinPoint jp, Exception ex) { /* Runs only on exception */ }

    @Around("execution(* com.ecommerce.service..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        log.info("Before: {}", pjp.getSignature().getName());
        try {
            Object result = pjp.proceed(); // MUST call — executes the real method
            log.info("Success: {}", pjp.getSignature().getName());
            return result;
        } catch (Exception e) {
            log.error("Failed: {}", pjp.getSignature().getName(), e);
            throw e; // Re-throw — don't swallow exceptions!
        }
    }
}
```

## Performance Logging Aspect
```java
@Aspect @Component @Slf4j
public class PerformanceLoggingAspect {
    private static final Logger PERF = LoggerFactory.getLogger("PERFORMANCE_LOGGER");
    private static final long SLOW_MS = 1000;

    @Around("execution(* com.ecommerce.orderservice.service..*(..))")
    public Object logServicePerformance(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long ms = System.currentTimeMillis() - start;
            if (ms > SLOW_MS) PERF.warn("SLOW: method={}, duration={}ms", method, ms);
            else PERF.debug("OK: method={}, duration={}ms", method, ms);
            MDC.put("lastMethodDurationMs", String.valueOf(ms));
            return result;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            PERF.error("FAILED: method={}, duration={}ms, error={}", method, ms, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.ecommerce.orderservice.repository..*(..))")
    public Object logRepositoryPerformance(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long ms = System.currentTimeMillis() - start;
        if (ms > 500) PERF.warn("SLOW QUERY: method={}, duration={}ms", method, ms);
        return result;
    }
}
```

## Custom @LogExecutionTime Annotation
```java
// Step 1: Define annotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogExecutionTime {
    String value() default "";
    long warnThresholdMs() default 1000;
}

// Step 2: Aspect
@Aspect @Component @Slf4j
public class ExecutionTimeAspect {
    @Around("@annotation(logExecutionTime)")
    public Object logTime(ProceedingJoinPoint pjp, LogExecutionTime logExecutionTime) throws Throwable {
        String label = logExecutionTime.value().isEmpty()
                ? pjp.getSignature().getName()
                : logExecutionTime.value();
        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long ms = System.currentTimeMillis() - start;
        if (ms > logExecutionTime.warnThresholdMs()) {
            log.warn("[SLOW] {}: {}ms (threshold: {}ms)", label, ms, logExecutionTime.warnThresholdMs());
        } else {
            log.info("[TIMING] {}: {}ms", label, ms);
        }
        return result;
    }
}

// Step 3: Usage
@LogExecutionTime(value = "createOrder", warnThresholdMs = 500)
public OrderResponse createOrder(OrderRequest req) { ... }
```

## Custom @Auditable Annotation
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action() default "";
    boolean logArgs() default false;
}

@Aspect @Component @Slf4j
public class AuditAspect {
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT_LOGGER");

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        String action = auditable.action().isEmpty() ? pjp.getSignature().getName() : auditable.action();
        String user = MDC.get("userId") != null ? MDC.get("userId") : "system";

        if (auditable.logArgs()) {
            AUDIT.info("ACTION user={} action={} args={}", user, action, Arrays.toString(pjp.getArgs()));
        } else {
            AUDIT.info("ACTION user={} action={}", user, action);
        }
        try {
            Object result = pjp.proceed();
            AUDIT.info("SUCCESS user={} action={}", user, action);
            return result;
        } catch (Exception e) {
            AUDIT.error("FAILED user={} action={} error={}", user, action, e.getMessage());
            throw e;
        }
    }
}

// Usage
@Auditable(action = "ORDER_DELETE", logArgs = false)
public void deleteOrder(Long id) { ... }

@Auditable(action = "ORDER_STATUS_CHANGE")
public void updateOrderStatus(Long id, OrderStatus status) { ... }
```

## Reusable Pointcut Definitions
```java
@Aspect
public class CommonPointcuts {
    @Pointcut("execution(* com.ecommerce.orderservice.service..*(..))")
    public void serviceLayer() {}

    @Pointcut("execution(* com.ecommerce.orderservice.repository..*(..))")
    public void repositoryLayer() {}

    @Pointcut("execution(* com.ecommerce.orderservice.controller..*(..))")
    public void controllerLayer() {}

    @Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void transactionalMethod() {}
}

// Use in another aspect
@Around("CommonPointcuts.serviceLayer()")
public Object logService(ProceedingJoinPoint pjp) throws Throwable { ... }
```

## Interview Q&A

**Q: What is AOP and why use it?**
Aspect-Oriented Programming separates cross-cutting concerns (logging, security, performance, auditing) from business logic. Without AOP: same logging code in every service method. With AOP: define once in an Aspect, apply to all matching methods via pointcut. DRY principle at the infrastructure level.

**Q: @Around vs @Before/@After?**
@Around: wraps entire method — must call `pjp.proceed()` or method doesn't execute. Most powerful — can modify args, result, handle exceptions. @Before: runs before, cannot stop execution. @AfterReturning: runs on success, access to return value. @AfterThrowing: runs on exception. Use @Around for timing/logging.

**Q: Why can't AOP intercept private methods?**
Spring AOP uses proxies (JDK dynamic proxy or CGLIB). Proxy wraps the bean — only public/protected methods accessible through proxy. Private methods called directly (bypass proxy). Same limitation for @Transactional, @Cacheable. Fix: use AspectJ compile-time weaving for private methods (complex setup).

**Q: Self-invocation problem with AOP?**
Calling an @Transactional (or @Cacheable, @Async) method from within the SAME class bypasses the proxy → AOP advice doesn't run. Example: `this.createOrder()` from within OrderServiceImpl → no transaction. Fix: inject self-reference via @Autowired or move method to another bean.

**Q: @Transactional is AOP?**
Yes! Spring implements @Transactional via AOP proxy — same mechanism as @Cacheable, @Async, @Retryable. The proxy starts a transaction before calling the real method, commits or rolls back after. That's why self-invocation breaks @Transactional.

**Q: Pointcut for all methods annotated with a custom annotation?**
`@annotation(com.ecommerce.annotation.Auditable)` — matches any method annotated with @Auditable regardless of class. Can combine: `@annotation(com.ecommerce.annotation.Auditable) && within(com.ecommerce.service..*)`.
