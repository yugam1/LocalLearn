# Phase 2 — Task 9: Scheduled Tasks (@Scheduled + Quartz)
**Estimated Time:** 1 hour
**Status:** ✅ Completed

---

## 🎯 What You Learn
1. @EnableScheduling and custom scheduler thread pool
2. fixedRate vs fixedDelay — timing model differences
3. initialDelay — startup warmup
4. Cron expression syntax and common patterns
5. Error handling in scheduled tasks (must not throw!)
6. @Scheduled thread pool — why single-threaded default is dangerous
7. Distributed scheduling — same task running on all instances
8. ShedLock — distributed lock for scheduled tasks
9. Quartz — enterprise scheduling with persistence
10. Scheduled task monitoring and metrics

---

## 🧠 Core Concepts

### Scheduling Types Compared

| Type | Interval Measured From | Overlap Possible? | Use Case |
|------|----------------------|------------------|----------|
| fixedRate | START of previous | Yes if task > rate | Heartbeat, status polls |
| fixedDelay | END of previous | No | DB cleanup, reports |
| cron | Wall clock time | No (single thread default) | Business hours tasks |
| initialDelay + fixedRate | First run delayed | Yes | Startup warmup |

### fixedRate Timing
```
fixedRate=10000, task takes 2s:
0s  → start (exec 1)
2s  → end (exec 1)
10s → start (exec 2)  ← measured from exec 1 START
12s → end (exec 2)
20s → start (exec 3)

fixedRate=10000, task takes 12s:
0s  → start (exec 1)
12s → end (exec 1)
12s → start (exec 2) immediately! (10s elapsed since exec 1 started)
```

### fixedDelay Timing
```
fixedDelay=10000, task takes 3s:
0s  → start (exec 1)
3s  → end (exec 1)
13s → start (exec 2) ← 10s after exec 1 ENDED
16s → end (exec 2)
26s → start (exec 3)
```

### Cron Expression Syntax
```
┌── second (0-59)
│ ┌── minute (0-59)
│ │ ┌── hour (0-23)
│ │ │ ┌── day-of-month (1-31)
│ │ │ │ ┌── month (1-12 or JAN-DEC)
│ │ │ │ │ ┌── day-of-week (0-7 or SUN-SAT)
│ │ │ │ │ │
* * * * * *
```

| Pattern | Cron | Notes |
|---------|------|-------|
| Every 30 seconds | `0/30 * * * * ?` | |
| Every minute | `0 * * * * ?` | |
| Every 5 minutes | `0 0/5 * * * ?` | |
| Daily at 2 AM | `0 0 2 * * ?` | |
| Weekdays at 9 AM | `0 0 9 ? * MON-FRI` | |
| First of month | `0 0 6 1 * ?` | |
| Every 30 min, 9-18 | `0 0/30 9-18 * * ?` | Business hours |
| Last day of month | `0 0 23 L * ?` | L = last |
| Every weekday | `0 0 8 ? * MON-FRI` | ? = no specific value |

Special characters: `*`=all, `?`=no specific, `-`=range, `,`=list, `/`=increment, `L`=last, `W`=weekday

### Default Scheduler = Single Thread (Dangerous!)
```
// WITHOUT custom pool: all @Scheduled tasks share ONE thread!
// Task A (takes 5s) + Task B (every 1s) → Task B delayed by A!
```

### Custom Scheduler Pool
```java
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);  // 10 concurrent scheduled tasks
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
```

### Distributed Scheduling Problem
```
Instance 1: @Scheduled runs every minute → sends daily report
Instance 2: @Scheduled runs every minute → sends daily report again!
Instance 3: @Scheduled runs every minute → sends daily report AGAIN!

Customer gets 3 emails. Disaster.
```

**Solutions:**
1. **ShedLock** — distributed lock via database
2. **Quartz with JDBC JobStore** — cluster-aware scheduler
3. **Leader election** — Zookeeper/etcd decides who runs tasks
4. **Manual DB flag** — check-and-set atomic flag

### ShedLock Pattern
```java
// pom.xml
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

// Config
@EnableSchedulerLock(defaultLockAtMostFor = "10m")

// Usage
@Scheduled(cron = "0 0 2 * * ?")
@SchedulerLock(name = "cleanupOldOrders", lockAtMostFor = "PT9M")
public void cleanupOldOrders() {
    // Only ONE instance runs this across the cluster
}

// DB table needed:
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```

### Error Handling (Critical!)
```java
@Scheduled(fixedRate = 60000)
public void scheduledTask() {
    // WRONG: uncaught exception STOPS future executions!
    riskyOperation();  // If this throws, task never runs again!
}

// RIGHT: always catch
@Scheduled(fixedRate = 60000)
public void scheduledTask() {
    try {
        riskyOperation();
    } catch (Exception e) {
        log.error("Scheduled task failed, will retry next interval", e);
        // Optional: send alert, increment failure counter
    }
    // Task continues running on schedule regardless!
}
```

---

## 🛠️ Implementation

### ScheduledTaskService — All Patterns
```java
@Service
@Slf4j
public class ScheduledTaskService {

    private final AtomicInteger fixedRateCounter = new AtomicInteger(0);

    // Pattern 1: fixedRate
    @Scheduled(fixedRate = 10000)
    public void executeFixedRate() {
        int count = fixedRateCounter.incrementAndGet();
        log.info("[FIXED-RATE] exec={}, time={}, thread={}",
                count, LocalDateTime.now(), Thread.currentThread().getName());
        try {
            Thread.sleep(2000);  // 2s task
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Pattern 2: fixedDelay
    @Scheduled(fixedDelay = 10000)
    public void executeFixedDelay() {
        log.info("[FIXED-DELAY] started: thread={}", Thread.currentThread().getName());
        try {
            Thread.sleep(3000);  // 3s task, next starts 10s after this finishes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Pattern 3: initialDelay
    @Scheduled(initialDelay = 5000, fixedRate = 15000)
    public void executeWithInitialDelay() {
        log.info("[INITIAL-DELAY] Wait 5s on startup, then every 15s");
    }

    // Pattern 4: cron — every minute
    @Scheduled(cron = "0 * * * * ?")
    public void executeCron() {
        log.info("[CRON] Every minute: {}", LocalDateTime.now());
    }

    // Pattern 5: error handling
    @Scheduled(fixedRate = 20000)
    public void executeWithErrorHandling() {
        try {
            if (Math.random() < 0.3) throw new RuntimeException("Simulated failure");
            log.info("[ERROR-HANDLING] Succeeded");
        } catch (Exception e) {
            log.error("[ERROR-HANDLING] Failed but will retry: {}", e.getMessage());
        }
    }
}
```

### Business Tasks
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderScheduledTaskService {

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Scheduled(cron = "0 0 2 * * ?")  // Daily 2 AM
    @Transactional
    public void cleanupOldPendingOrders() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            long count = orderRepository.findByStatus(OrderStatus.PENDING).stream()
                    .filter(o -> o.getOrderDate().isBefore(cutoff))
                    .count();
            log.info("Cleanup: {} pending orders older than 7 days found", count);
        } catch (Exception e) {
            log.error("Cleanup failed", e);
        }
    }

    @Scheduled(cron = "0 0 23 * * ?")  // Daily 11 PM
    public void generateDailySummary() {
        try {
            LocalDateTime start = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
            long todayOrders = orderRepository.findByOrderDateBetween(start, LocalDateTime.now()).size();
            log.info("Daily summary: {} orders today", todayOrders);
        } catch (Exception e) {
            log.error("Daily summary failed", e);
        }
    }

    @Scheduled(cron = "0 0/5 * * * ?")  // Every 5 minutes
    public void monitorPendingOrders() {
        try {
            long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
            if (pendingCount > 100) log.warn("HIGH PENDING ORDERS: {}", pendingCount);
        } catch (Exception e) {
            log.error("Monitor failed", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")  // Daily 3 AM
    @Transactional
    public void cleanupOldProcessedEvents() {
        try {
            processedEventRepository.deleteByProcessedAtBefore(LocalDateTime.now().minusDays(30));
            log.info("Cleaned up processed events older than 30 days");
        } catch (Exception e) {
            log.error("Event cleanup failed", e);
        }
    }
}
```

### Monitoring
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTaskMonitoringService {

    private final ThreadPoolTaskScheduler taskScheduler;

    public Map<String, Object> getSchedulerMetrics() {
        return Map.of(
            "poolSize", taskScheduler.getPoolSize(),
            "activeCount", taskScheduler.getActiveCount(),
            "scheduledTaskCount", taskScheduler.getScheduledThreadPoolExecutor().getTaskCount(),
            "completedTaskCount", taskScheduler.getScheduledThreadPoolExecutor().getCompletedTaskCount()
        );
    }

    @Scheduled(cron = "0 0/5 * * * ?")
    public void logSchedulerStatistics() {
        var metrics = getSchedulerMetrics();
        log.info("Scheduler stats: pool={}, active={}, total={}, completed={}",
                metrics.get("poolSize"), metrics.get("activeCount"),
                metrics.get("scheduledTaskCount"), metrics.get("completedTaskCount"));
    }
}
```

---

## 🧪 Testing

```bash
# Start app and watch logs for 2 minutes
./mvnw spring-boot:run

# See in console:
# 10:00:00 [scheduled-2] [FIXED-RATE] exec=1
# 10:00:00 [scheduled-4] [FIXED-DELAY] started
# 10:00:05 [scheduled-1] [INITIAL-DELAY] ...
# 10:00:10 [scheduled-3] [FIXED-RATE] exec=2  ← exactly 10s from exec=1 START
# 10:00:13 [scheduled-5] [FIXED-DELAY] started ← 3s task + 10s delay
# 10:01:00 [scheduled-6] [CRON] Every minute

# Multiple threads visible: scheduled-1, scheduled-2, etc.
# Each task on separate thread (custom pool working!)

# Metrics
curl http://localhost:8080/api/v1/scheduled-tasks/metrics
```

---

## ✅ Completion Checklist
- [ ] SchedulingConfig implements SchedulingConfigurer
- [ ] ThreadPoolTaskScheduler: poolSize=10, prefix=scheduled-
- [ ] waitForTasksToCompleteOnShutdown=true
- [ ] ScheduledTaskService: fixedRate, fixedDelay, initialDelay, cron examples
- [ ] Error handling: every @Scheduled method has try-catch
- [ ] OrderScheduledTaskService: cleanup, summary, monitor
- [ ] cleanupOldProcessedEvents: 30-day retention
- [ ] ScheduledTaskMonitoringService: metrics
- [ ] ScheduledTaskController: /metrics, /executions
- [ ] App starts, all tasks execute at correct intervals
- [ ] Multiple thread names visible (scheduled-N)
- [ ] Error in one task doesn't affect others

---

## 💬 Interview Q&A

**Q: fixedRate vs fixedDelay — what's the key difference?**
A: fixedRate measures interval from START of previous execution. fixedDelay measures from END. If task takes 12s with fixedRate=10s, next starts immediately when current finishes (no wait). fixedDelay=10s with 12s task → next starts 22s after previous started. Use fixedDelay to prevent task overlap.

**Q: What happens if a @Scheduled method throws an uncaught exception?**
A: For a fixedRate/fixedDelay task — the current execution stops and the exception is logged, but future executions continue as scheduled. For a cron task — same. The scheduler doesn't stop. However, best practice is always catch internally and log, for visibility.

**Q: Why configure a custom thread pool for scheduling?**
A: Spring's default scheduler uses a single thread. If you have 5 scheduled tasks and one takes 30 seconds, the other 4 are blocked until it finishes. A pool of 10 lets all tasks run concurrently on time.

**Q: How do you prevent duplicate execution in a clustered environment?**
A: @Scheduled runs on every instance. Solutions: ShedLock (distributed lock via DB — only one instance acquires lock per schedule), Quartz with clustered JDBC JobStore (Quartz handles coordination), leader election pattern. ShedLock is simplest to add to existing apps.

**Q: What is a cron expression for "every weekday at 9 AM"?**
A: `0 0 9 ? * MON-FRI`. Format: second minute hour day-of-month month day-of-week. The `?` is required in either day-of-month or day-of-week when the other is specified.

**Q: When would you use Quartz over @Scheduled?**
A: Quartz for: persistent jobs (survive app restart), clustered execution, dynamic scheduling (add/remove jobs at runtime), complex dependencies between jobs, misfire handling (what to do if job was missed during downtime). @Scheduled for simple periodic tasks in single-instance apps.

---

## 🔗 Next Task
**Task 10: Apache Kafka Basics — Producer & Consumer** — Kafka architecture, KafkaTemplate, @KafkaListener, JSON serialization, manual ack, correlation ID propagation to consumers.
