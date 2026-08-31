package com.ecommerce.orderservice.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects correlation ID / user ID / request metadata into MDC for every
 * HTTP request so all log lines emitted while handling it can be correlated.
 * See docs/phase2_task7.md.
 */
@Component
@Order(1)
@Slf4j
public class MDCFilter extends GenericFilterBean {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String USER_ID_HEADER = "X-User-ID";
    private static final long SLOW_REQUEST_THRESHOLD_MS = 1000L;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = "REQ-" + UUID.randomUUID();
            }
            MDC.put("correlationId", correlationId);
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

            String userId = httpRequest.getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                MDC.put("userId", userId);
            }

            MDC.put("method", httpRequest.getMethod());
            MDC.put("path", httpRequest.getRequestURI());
            MDC.put("ipAddress", getClientIp(httpRequest));

            log.info("Incoming: method={}, path={}", httpRequest.getMethod(), httpRequest.getRequestURI());

            long start = System.currentTimeMillis();
            try {
                chain.doFilter(request, response);
            } finally {
                long duration = System.currentTimeMillis() - start;
                log.info("Completed: status={}, duration={}ms", httpResponse.getStatus(), duration);
                if (duration > SLOW_REQUEST_THRESHOLD_MS) {
                    log.warn("SLOW REQUEST: path={}, duration={}ms", httpRequest.getRequestURI(), duration);
                }
            }
        } finally {
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
