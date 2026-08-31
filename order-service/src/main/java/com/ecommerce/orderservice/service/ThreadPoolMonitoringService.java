package com.ecommerce.orderservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Exposes live metrics/health for the pools configured in
 * {@link com.ecommerce.orderservice.config.AsyncConfig}, so saturation can
 * be observed instead of discovered via {@code RejectedExecutionException}
 * in production. See docs/phase2_task8.md, "Thread Pool Monitoring".
 */
@Service
@Slf4j
public class ThreadPoolMonitoringService {

    private final ThreadPoolTaskExecutor taskExecutor;
    private final ThreadPoolTaskExecutor emailExecutor;
    private final ThreadPoolTaskExecutor inventoryExecutor;
    private final ThreadPoolTaskExecutor analyticsExecutor;

    public ThreadPoolMonitoringService(@Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor,
                                        @Qualifier("emailExecutor") ThreadPoolTaskExecutor emailExecutor,
                                        @Qualifier("inventoryExecutor") ThreadPoolTaskExecutor inventoryExecutor,
                                        @Qualifier("analyticsExecutor") ThreadPoolTaskExecutor analyticsExecutor) {
        this.taskExecutor = taskExecutor;
        this.emailExecutor = emailExecutor;
        this.inventoryExecutor = inventoryExecutor;
        this.analyticsExecutor = analyticsExecutor;
    }

    public Map<String, Object> getAllMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("default", getMetrics(taskExecutor));
        metrics.put("email", getMetrics(emailExecutor));
        metrics.put("inventory", getMetrics(inventoryExecutor));
        metrics.put("analytics", getMetrics(analyticsExecutor));
        return metrics;
    }

    public Map<String, String> getAllHealth() {
        Map<String, String> health = new LinkedHashMap<>();
        health.put("default", checkHealth(taskExecutor));
        health.put("email", checkHealth(emailExecutor));
        health.put("inventory", checkHealth(inventoryExecutor));
        health.put("analytics", checkHealth(analyticsExecutor));
        return health;
    }

    public Map<String, Object> getMetrics(ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("corePoolSize", pool.getCorePoolSize());
        metrics.put("maxPoolSize", pool.getMaximumPoolSize());
        metrics.put("activeThreads", pool.getActiveCount());
        metrics.put("poolSize", pool.getPoolSize());
        metrics.put("queueSize", pool.getQueue().size());
        metrics.put("completedTasks", pool.getCompletedTaskCount());
        metrics.put("utilizationPercent", utilization(pool));
        return metrics;
    }

    public String checkHealth(ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        double poolUtil = utilization(pool);
        int queueCapacity = executor.getQueueCapacity();
        double queueUtil = queueCapacity == 0 ? 0.0 : (double) pool.getQueue().size() / queueCapacity * 100;

        if (poolUtil > 90 && queueUtil > 90) {
            return "CRITICAL";
        }
        if (poolUtil > 80 || queueUtil > 80) {
            return "WARNING";
        }
        return "HEALTHY";
    }

    private double utilization(ThreadPoolExecutor pool) {
        return pool.getMaximumPoolSize() == 0 ? 0.0
                : (double) pool.getActiveCount() / pool.getMaximumPoolSize() * 100;
    }
}
