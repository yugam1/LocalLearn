package com.ecommerce.orderservice.util;

import org.slf4j.MDC;

/**
 * Small logging helpers: MDC access + PII masking so services never log raw
 * emails / sensitive values (see docs/phase2_task7.md, "What to Log for
 * Security").
 */
public final class LoggingUtils {

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String USER_ID_KEY = "userId";

    private LoggingUtils() {
    }

    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    /**
     * Masks an email for safe logging: {@code j***@example.com}.
     * Never log raw PII in production logs.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(Math.max(at, 0));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
