package com.ecommerce.orderservice.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting method-execution-time logging for the whole service layer.
 * Writes to the PERFORMANCE_LOGGER named logger (routed to a dedicated file
 * by logback-spring.xml) rather than the class's own logger, so performance
 * data can be retained/rotated independently. See docs/phase2_task7.md.
 */
@Aspect
@Component
public class PerformanceLoggingAspect {

    private static final Logger PERF = LoggerFactory.getLogger("PERFORMANCE_LOGGER");
    private static final long SLOW_THRESHOLD_MS = 1000L;

    @Around("execution(* com.ecommerce.orderservice.service..*(..))")
    public Object logServicePerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long durationMs = System.currentTimeMillis() - start;
            if (durationMs > SLOW_THRESHOLD_MS) {
                PERF.warn("SLOW: method={}, duration={}ms", method, durationMs);
            } else {
                PERF.debug("method={}, duration={}ms", method, durationMs);
            }
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            PERF.error("FAILED: method={}, duration={}ms, error={}", method, durationMs, e.getMessage());
            throw e;
        }
    }
}
