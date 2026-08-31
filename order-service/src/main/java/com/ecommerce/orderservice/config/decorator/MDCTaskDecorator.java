package com.ecommerce.orderservice.config.decorator;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates the calling thread's MDC context (correlation ID, user ID,
 * ...) onto an @Async worker thread, which otherwise starts with an empty
 * MDC. Wire into a ThreadPoolTaskExecutor via
 * {@code executor.setTaskDecorator(new MDCTaskDecorator())} — done by the
 * async executor configuration added in a later task. See
 * docs/phase2_task7.md, "MDC in Async Threads".
 */
public class MDCTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
