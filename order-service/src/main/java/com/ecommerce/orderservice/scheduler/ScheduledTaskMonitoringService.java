package com.ecommerce.orderservice.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports on the custom scheduler pool configured in
 * {@link com.ecommerce.orderservice.config.SchedulingConfig}. See
 * docs/phase2_task9.md, "Monitoring".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskMonitoringService {

    private final ThreadPoolTaskScheduler taskScheduler;

    public Map<String, Object> getSchedulerMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("poolSize", taskScheduler.getPoolSize());
        metrics.put("activeCount", taskScheduler.getActiveCount());
        metrics.put("scheduledTaskCount", taskScheduler.getScheduledThreadPoolExecutor().getTaskCount());
        metrics.put("completedTaskCount", taskScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount());
        return metrics;
    }

    @Scheduled(cron = "0 0/5 * * * ?")
    public void logSchedulerStatistics() {
        try {
            Map<String, Object> metrics = getSchedulerMetrics();
            log.info("[SCHEDULER-STATS] pool={}, active={}, total={}, completed={}",
                    metrics.get("poolSize"), metrics.get("activeCount"),
                    metrics.get("scheduledTaskCount"), metrics.get("completedTaskCount"));
        } catch (Exception e) {
            log.error("[SCHEDULER-STATS] failed to collect metrics", e);
        }
    }
}
