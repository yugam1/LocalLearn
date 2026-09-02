# Phase 2 — Task 9: Scheduled Tasks (@Scheduled + Quartz)
**Estimated Time:** 1 hour | **Status:** ✅ Completed

---

## @Scheduled Types

### Fixed Rate — From START of previous execution
```java
@Scheduled(fixedRate = 10000) // Every 10s from previous START
public void executeFixedRate() {
    Thread.sleep(2000); // 2s task
}
// Executes at: 0s, 10s, 20s, 30s... (regardless of task duration)
// RISK: if task takes >10s, next starts immediately (no gap)
```

### Fixed Delay — From END of previous execution
```java
@Scheduled(fixedDelay = 10000) // 10s after previous END
public void executeFixedDelay() {
    Thread.sleep(3000); // 3s task
}
// Executes at: 0s, 13s, 26s... (end_time + delay)
// BENEFIT: No overlap ever possible
```

### Initial Delay
```java
@Scheduled(initialDelay = 5000, fixedRate = 15000)
public void executeWithDelay() { }
// Wait 5s on startup, then every 15s — lets app fully initialize
```

### Cron Expression
```java
@Scheduled(cron = "0 0 2 * * ?")        // Daily at 2:00 AM
@Scheduled(cron = "0 0/15 * * * ?")     // Every 15 minutes
@Scheduled(cron = "0 0 9 ? * MON-FRI") // Weekdays at 9 AM
@Scheduled(cron = "0 0/30 9-18 * * ?") // Every 30 min, 9AM-6PM
@Scheduled(cron = "0 59 23 L * ?")      // Last day of month 11:59 PM
@Scheduled(cron = "0,30 * * * * ?")     // At :00 and :30 of every minute
```

**Cron format:** `second minute hour day-of-month month day-of-week`

**Special chars:**
- `*` = every value
- `?` = no specific value (required when using day-of-week or day-of-month)
- `-` = range (MON-FRI)
- `,` = list (1,3,5)
- `/` = increment (0/15 = every 15 starting at 0)
- `L` = last day of month

---

## Fixed Rate vs Fixed Delay — Visual

```
fixedRate=10s, task=2s:
t=0   Start→|task 2s|        10s        |Start→|task 2s|...
             t=2             t=10         t=10

fixedDelay=10s, task=3s:
t=0   Start→|task 3s|←10s delay→|Start→|task 3s|←10s→...
             t=3               t=13

fixedRate=5s, task=7s (task > rate):
t=0   Start→|task...7s...|Start immediately→|task...|...
             t=7           t=7 (no wait!)
```

**Rule:** Use fixedDelay when tasks must NOT overlap. Use fixedRate for time-critical heartbeats.

---

## Custom Thread Pool (CRITICAL!)

**Default scheduler = single thread — only ONE task runs at a time!**

```java
@Configuration
@EnableScheduling
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }

    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);                   // 10 concurrent scheduled tasks
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        log.info("Task scheduler configured: poolSize=10");
        return scheduler;
    }
}
```

---

## Business Scheduled Tasks

```java
@Service @RequiredArgsConstructor @Slf4j
public class OrderScheduledTaskService {

    private final OrderRepository orderRepository;

    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    @Transactional
    public void cleanupOldPendingOrders() {
        log.info("Starting cleanup of old pending orders");
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            long count = orderRepository.findByStatus(OrderStatus.PENDING).stream()
                    .filter(o -> o.getOrderDate().isBefore(cutoff)).count();
            log.info("Found {} stale pending orders (>7 days)", count);
            // In prod: cancel or archive them
        } catch (Exception e) {
            log.error("Cleanup failed", e); // NEVER let exception propagate!
        }
    }

    @Scheduled(cron = "0 0 23 * * ?") // Daily at 11 PM
    public void generateDailySummary() {
        log.info("Generating daily order summary");
        try {
            long total = orderRepository.count();
            long pending = orderRepository.countByStatus(OrderStatus.PENDING);
            log.info("Daily summary: total={}, pending={}", total, pending);
        } catch (Exception e) {
            log.error("Daily summary failed", e);
        }
    }

    @Scheduled(fixedRate = 60000) // Every 60s
    public void healthCheck() {
        try {
            orderRepository.count(); // Quick DB ping
            log.debug("Health check passed");
        } catch (Exception e) {
            log.error("Health check FAILED — database unreachable", e);
        }
    }

    @Scheduled(cron = "0 0/5 * * * ?") // Every 5 minutes
    public void checkOrderStatusAlerts() {
        try {
            long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
            if (pendingCount > 100) {
                log.warn("HIGH PENDING COUNT: {} pending orders (threshold: 100)", pendingCount);
                // alertService.sendAlert(...)
            }
        } catch (Exception e) {
            log.error("Status check failed", e);
        }
    }
}
```

---

## Error Handling Pattern

```java
// CRITICAL: Always catch exceptions in scheduled tasks
// An uncaught exception stops THAT execution but scheduler continues
// However: some versions stop future executions too — don't risk it!

@Scheduled(fixedRate = 20000)
public void riskySyncTask() {
    log.info("Starting sync task");
    try {
        riskyExternalApiCall();
        log.info("Sync task completed");
    } catch (HttpClientErrorException e) {
        log.warn("External API returned error: {}", e.getStatusCode());
        // Tolerate and continue
    } catch (Exception e) {
        log.error("Sync task failed unexpectedly", e);
        // Alert, metric, etc. — but don't rethrow
    }
}
```

---

## Distributed Scheduling Problem

```
Instance 1: sends daily report email at 2 AM
Instance 2: sends SAME email at 2 AM  ← duplicate!
Instance 3: sends SAME email at 2 AM  ← duplicate!
```

### Solution 1: ShedLock (Simplest)

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>5.10.0</version>
</dependency>
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-provider-jdbc-template</artifactId>
    <version>5.10.0</version>
</dependency>
```

```java
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@Configuration
public class ShedLockConfig {
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}

@Scheduled(cron = "0 0 2 * * ?")
@SchedulerLock(name = "dailyReport", lockAtMostFor = "5m", lockAtLeastFor = "1m")
public void sendDailyReport() {
    // Only ONE instance runs this — others skip (lock in DB)
}
```

```sql
-- Required table
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

### Solution 2: Database Flag (Manual)
```java
@Scheduled(fixedRate = 60000)
public void processWithManualLock() {
    String lockKey = "processOrders";
    if (distributedLockService.tryLock(lockKey, Duration.ofSeconds(55))) {
        try {
            doProcessOrders();
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }
}
```

---

## Dynamic Scheduling at Runtime

```java
@Service @RequiredArgsConstructor
public class DynamicSchedulingService {
    private final TaskScheduler taskScheduler;
    private ScheduledFuture<?> scheduledFuture;

    public void startTask(String cronExpression) {
        scheduledFuture = taskScheduler.schedule(
            this::doWork,
            new CronTrigger(cronExpression)
        );
        log.info("Task scheduled: cron={}", cronExpression);
    }

    public void stopTask() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false); // false = don't interrupt if running
            log.info("Task stopped");
        }
    }

    public void reschedule(String newCron) {
        stopTask();
        startTask(newCron);
    }

    private void doWork() { log.info("Dynamic task executing"); }
}
```

---

## Monitoring Scheduled Tasks

```java
@Service @RequiredArgsConstructor @Slf4j
public class ScheduledTaskMonitoringService {
    private final ThreadPoolTaskScheduler taskScheduler;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void logStatistics() {
        ScheduledThreadPoolExecutor pool = taskScheduler.getScheduledThreadPoolExecutor();
        log.info("Scheduler: pool={}, active={}, queued={}, completed={}",
                pool.getPoolSize(), pool.getActiveCount(),
                pool.getQueue().size(), pool.getCompletedTaskCount());
    }

    public Map<String, Object> getMetrics() {
        ScheduledThreadPoolExecutor pool = taskScheduler.getScheduledThreadPoolExecutor();
        return Map.of(
            "poolSize", pool.getPoolSize(),
            "activeCount", pool.getActiveCount(),
            "queueSize", pool.getQueue().size(),
            "completedTaskCount", pool.getCompletedTaskCount()
        );
    }
}
```

---

## Interview Q&A

**Q: fixedRate vs fixedDelay?**
fixedRate: interval measured from START of previous execution — can overlap if task > rate. fixedDelay: interval measured from END of previous execution — no overlap possible. Use fixedDelay when tasks must not overlap (DB cleanup). fixedRate for heartbeats/polling where timing matters more than gap.

**Q: What happens if a scheduled task throws an exception?**
Exception logged by Spring, that execution ends, but scheduler continues — next scheduled execution fires normally. Best practice: wrap entire body in try-catch, log error, handle gracefully. NEVER let exception escape scheduled task body.

**Q: Why configure custom thread pool for @Scheduled?**
Default scheduler is SINGLE THREAD. One long-running task blocks all other scheduled tasks. With 10 scheduled tasks and single thread, they queue behind each other. Custom pool (size 10) allows all to run concurrently.

**Q: How do you prevent duplicate execution in clustered environment?**
ShedLock: distributed lock via DB/Redis, only one instance acquires lock and runs the task. Others skip. Lock auto-released after `lockAtMostFor` duration (prevents stale lock if node crashes).

**Q: initialDelay use case?**
Wait for application to fully initialize before first run. Examples: DB warmup, cache population, wait for Kafka connections to establish. Prevents task running on half-initialized application.

**Q: Cron expression for every 15 minutes?**
`0 0/15 * * * ?` — at second 0, every 15 minutes, any hour, any day, any month, any day of week.

**Q: What is Quartz vs @Scheduled?**
@Scheduled: simple, in-memory, stateless, single-instance, lost on restart. Quartz: persistent jobs (JDBC store), clustered, dynamic scheduling, misfire handling, complex dependencies. Use @Scheduled for simple periodic tasks. Quartz for enterprise requirements (high availability, persistence, dynamic).
