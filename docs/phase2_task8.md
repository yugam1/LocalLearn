# Phase 2 — Task 8: Thread Pools & ExecutorService Configuration
**Estimated Time:** 1.5 hours | **Status:** ✅ Completed

---

## Why Thread Pools?

Creating a thread costs ~1-2ms and ~1MB RAM. Thread pools reuse threads — near-zero overhead per task.

Node.js parallel: Your NPM worker pools use the same principle — pre-created workers, task queue, configurable concurrency.

---

## Thread Pool Types

| Type | Threads | Queue | Use |
|---|---|---|---|
| `newFixedThreadPool(n)` | Fixed | Unbounded ⚠️ | Avoid — OOM risk |
| `newCachedThreadPool()` | Unlimited ⚠️ | None | Avoid — thread explosion |
| `newScheduledThreadPool(n)` | Fixed | Scheduled | Cron-like tasks |
| **`ThreadPoolTaskExecutor`** | Configurable | Bounded ✅ | **Production — always use this** |

---

## ThreadPoolTaskExecutor — Production Configuration

```java
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);          // Min threads always alive
        executor.setMaxPoolSize(50);           // Max threads allowed
        executor.setQueueCapacity(100);        // Pending tasks before rejection
        executor.setThreadNamePrefix("async-default-");
        executor.setKeepAliveSeconds(60);      // Idle thread TTL above core
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(loggingRejectionHandler("default"));
        executor.setTaskDecorator(new MDCTaskDecorator()); // MDC propagation!
        executor.initialize();
        return executor;
    }

    @Bean(name = "emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(5); e.setMaxPoolSize(10); e.setQueueCapacity(200);
        e.setThreadNamePrefix("async-email-");
        e.setTaskDecorator(new MDCTaskDecorator());
        e.initialize(); return e;
    }

    @Bean(name = "inventoryExecutor")
    public Executor inventoryExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(15); e.setMaxPoolSize(30); e.setQueueCapacity(50); // Small queue = fail fast
        e.setThreadNamePrefix("async-inventory-");
        e.setTaskDecorator(new MDCTaskDecorator());
        e.initialize(); return e;
    }

    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(3); e.setMaxPoolSize(10); e.setQueueCapacity(500); // Large queue = non-critical
        e.setThreadNamePrefix("async-analytics-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        e.setTaskDecorator(new MDCTaskDecorator());
        e.initialize(); return e;
    }

    private RejectedExecutionHandler loggingRejectionHandler(String poolName) {
        return (runnable, executor) -> {
            log.error("Task rejected from {} pool: queue={}, active={}", poolName,
                    executor.getQueue().size(), executor.getActiveCount());
            if (!executor.isShutdown()) runnable.run(); // Fallback: CallerRunsPolicy
        };
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Async method {} threw uncaught exception: {}", method.getName(), ex.getMessage(), ex);
    }
}
```

---

## Thread Pool Sizing

**CPU-bound tasks:** `threads = cores + 1`
**I/O-bound tasks:** `threads = cores × (1 + wait_time / cpu_time)`
- 8 cores, 90% I/O waiting → `8 × (1 + 9) = 80 threads`

---

## Rejection Policies

| Policy | Behavior | Use |
|---|---|---|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` | Want immediate failure signal |
| **`CallerRunsPolicy`** | Caller thread executes task | **Production — natural backpressure** |
| `DiscardPolicy` | Silently drops task | Non-critical analytics |
| `DiscardOldestPolicy` | Drops oldest queued task | Real-time data (latest matters most) |

CallerRunsPolicy is best for production: naturally throttles the caller when pool is saturated, no task lost.

---

## Queue Sizing

```
Queue size = peak_RPS × avg_task_duration_seconds × safety_factor

Example: 100 req/s, 500ms avg task → 100 × 0.5 × 2 = 100 tasks
```

**Unbounded queue (LinkedBlockingQueue without capacity) = DANGER:**
- Tasks pile up infinitely → OutOfMemoryError
- No backpressure signal

**Always set capacity!**

---

## Task Lifecycle in Pool

```
Task submitted
    ↓
< corePoolSize active? → Create new thread → Execute
    ↓
>= corePoolSize? → Queue task (if capacity available)
    ↓
Queue full? → Expand pool up to maxPoolSize
    ↓
Pool at maxPoolSize AND queue full? → REJECT (rejection policy)
```

---

## @Async Usage

```java
// RULES:
// 1. Must be PUBLIC method
// 2. Must be called from DIFFERENT bean (Spring proxy requirement)
// 3. Return void OR CompletableFuture

@Async("emailExecutor")
public CompletableFuture<Void> sendEmailAsync(Long orderId) {
    log.info("Email: orderId={}, thread={}", orderId, Thread.currentThread().getName());
    Thread.sleep(500); // simulate work
    return CompletableFuture.completedFuture(null);
}

@Async("inventoryExecutor")
public CompletableFuture<Void> updateInventoryAsync(Long orderId) {
    Thread.sleep(200);
    return CompletableFuture.completedFuture(null);
}

// Parallel execution
CompletableFuture<Void> email = asyncService.sendEmailAsync(orderId);
CompletableFuture<Void> inventory = asyncService.updateInventoryAsync(orderId);
CompletableFuture.allOf(email, inventory).join(); // Wait for both
```

---

## CompletableFuture Patterns

### Sequential Pipeline
```java
CompletableFuture<String> result = CompletableFuture
    .supplyAsync(() -> validate(input), executor)
    .thenApplyAsync(v -> transform(v), executor)
    .thenApplyAsync(v -> enrich(v), executor);
// Total time = sum of steps
```

### Parallel (allOf)
```java
CompletableFuture<String> op1 = CompletableFuture.supplyAsync(() -> op1(), executor);
CompletableFuture<String> op2 = CompletableFuture.supplyAsync(() -> op2(), executor);
CompletableFuture.allOf(op1, op2).thenApply(v -> combine(op1.join(), op2.join()));
// Total time = max of steps (parallel!)
```

### Race (anyOf)
```java
CompletableFuture.anyOf(
    CompletableFuture.supplyAsync(() -> provider1(input), executor),
    CompletableFuture.supplyAsync(() -> provider2(input), executor)
).thenApply(result -> (String) result);
// Returns whichever finishes first
```

### Exception Handling
```java
CompletableFuture.supplyAsync(() -> riskyOp())
    .exceptionally(ex -> { log.error("Failed", ex); return "FALLBACK"; })
    .orTimeout(2, TimeUnit.SECONDS)
    .exceptionally(ex -> ex instanceof TimeoutException ? "CACHED" : "ERROR");
```

---

## Thread Pool Monitoring

```java
@Service
public class ThreadPoolMonitoringService {
    private final Executor taskExecutor;

    public Map<String, Object> getMetrics() {
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) taskExecutor).getThreadPoolExecutor();
        return Map.of(
            "activeThreads", pool.getActiveCount(),
            "poolSize", pool.getPoolSize(),
            "maxPoolSize", pool.getMaximumPoolSize(),
            "queueSize", pool.getQueue().size(),
            "completedTasks", pool.getCompletedTaskCount(),
            "utilizationPct", (double) pool.getActiveCount() / pool.getMaximumPoolSize() * 100
        );
    }

    public String checkHealth() {
        ThreadPoolExecutor pool = ((ThreadPoolTaskExecutor) taskExecutor).getThreadPoolExecutor();
        double util = (double) pool.getActiveCount() / pool.getMaximumPoolSize() * 100;
        double queueUtil = (double) pool.getQueue().size() /
                (pool.getQueue().size() + pool.getQueue().remainingCapacity()) * 100;
        if (util > 90 && queueUtil > 90) return "CRITICAL";
        if (util > 80 || queueUtil > 80) return "WARNING";
        return "HEALTHY";
    }
}
```

---

## MDCTaskDecorator

```java
public class MDCTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) MDC.setContextMap(contextMap);
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

---

## Interview Q&A

**Q: How do you size a thread pool?**
CPU-bound: cores+1 (more = context-switching overhead). I/O-bound: cores × (1 + wait/CPU). Start with 10-20, monitor active threads and queue size, adjust. Monitor: utilization >80% → increase; many idle → decrease.

**Q: Core vs max pool size?**
Core: always-alive threads. Max: ceiling. Thread creation order: 1→core threads, 2→queue, 3→beyond core up to max, 4→rejection. Threads above core terminate after keepAlive seconds.

**Q: Why CallerRunsPolicy for production?**
Saturated pool? Caller thread executes the task. Naturally slows down the caller (backpressure). No task lost. No exception thrown. System degrades gracefully under load.

**Q: @Async caveats?**
Public methods only (proxy limitation). Must be called from different bean (self-calls bypass proxy). Return void or CompletableFuture. Unchecked exceptions swallowed for void methods — configure AsyncUncaughtExceptionHandler.

**Q: allOf vs anyOf?**
allOf: completes when ALL futures done — combine results. anyOf: completes when ANY future done — use for racing providers, return fastest.

**Q: How to propagate MDC to async threads?**
MDC is thread-local — async threads start empty. MDCTaskDecorator: capture context before submit, restore in async thread at start, clear in finally. Apply via `executor.setTaskDecorator(new MDCTaskDecorator())`.

**Q: Why multiple thread pools?**
Isolation: slow email service doesn't starve inventory operations. Different priorities: critical ops (small queue, fail fast) vs analytics (large queue, discard old). Independent monitoring per task type.
