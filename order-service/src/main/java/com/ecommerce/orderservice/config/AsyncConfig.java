package com.ecommerce.orderservice.config;

import com.ecommerce.orderservice.config.decorator.MDCTaskDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Thread pool configuration for {@code @Async} method execution. {@code
 * @EnableAsync} is already declared on {@link com.ecommerce.orderservice.OrderServiceApplication}.
 *
 * <p>Four isolated pools so a burst on one task type (e.g. analytics
 * report generation) can never starve another (e.g. inventory updates).
 * See docs/phase2_task8.md, "Multiple pools for isolation".
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Default pool, injected wherever {@code @Async} doesn't name a
     * qualifier and returned as Spring's application-wide async executor.
     */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-default-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // CallerRunsPolicy with logging: natural backpressure instead of
        // silently dropping or throwing under load. See docs/phase2_task8.md,
        // "Rejection Policies".
        executor.setRejectedExecutionHandler((task, pool) -> {
            log.error("Task rejected on default pool! queueSize={}, activeCount={}",
                    pool.getQueue().size(), pool.getActiveCount());
            if (!pool.isShutdown()) {
                task.run();
            }
        });
        executor.setTaskDecorator(new MDCTaskDecorator());
        executor.initialize();
        return executor;
    }

    /** Non-critical, can queue deeply and wait — email delivery isn't latency-sensitive. */
    @Bean(name = "emailExecutor")
    public ThreadPoolTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-email-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MDCTaskDecorator());
        executor.initialize();
        return executor;
    }

    /** High priority, small queue — fail fast (grow threads) rather than delay stock updates. */
    @Bean(name = "inventoryExecutor")
    public ThreadPoolTaskExecutor inventoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-inventory-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MDCTaskDecorator());
        executor.initialize();
        return executor;
    }

    /** Low priority, large queue, discard-oldest — analytics can lose stale data under load. */
    @Bean(name = "analyticsExecutor")
    public ThreadPoolTaskExecutor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-analytics-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setTaskDecorator(new MDCTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Catches exceptions from {@code void}-returning {@code @Async} methods,
     * which otherwise vanish silently — {@code CompletableFuture}-returning
     * methods carry their exception in the future instead.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "Async method '{}' threw uncaught exception: {}", method.getName(), ex.getMessage(), ex);
    }
}
