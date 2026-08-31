# Phase 2 — Task 8: Thread Pools & ExecutorService Configuration
**Estimated Time:** 1.5 hours
**Status:** ✅ Completed

---

## 🎯 What You Learn
1. Why thread pools instead of creating threads per task
2. Pool types: Fixed, Cached, Scheduled, Custom
3. ThreadPoolTaskExecutor — Spring's wrapper
4. Core vs max pool size, queue capacity, keep-alive
5. Rejection policies — what happens when pool saturates
6. @Async — Spring's async method execution
7. @Async caveats — proxy, same-class calls, return types
8. CompletableFuture — sequential, parallel, race, error handling
9. Multiple pools for isolation (email, inventory, analytics)
10. MDCTaskDecorator — correlation IDs in async threads
11. Thread pool monitoring and health checks

---

## 🧠 Core Concepts

### Why Thread Pools?
```java
// BAD: create thread per request
new Thread(() -> processOrder(order)).start();
// 1000 requests = 1000 threads = ~1GB RAM + context switching chaos

// GOOD: pool of reusable threads
executor.submit(() -> processOrder(order));
// 1000 requests = 10 threads handle them all sequentially from queue
```

**Like Node.js worker pools!**
```javascript
// Node.js
const { Worker } = require('worker_threads');
const workers = Array(10).fill(null).map(() => new Worker('./worker.js'));

// Java equivalent
ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
pool.setCorePoolSize(10);
```

### Pool Parameters
```
corePoolSize  = threads always alive (min)
maxPoolSize   = max threads ever created
queueCapacity = pending task buffer
keepAlive     = how long idle threads above core stay alive

Flow when task submitted:
1. If active threads < corePoolSize → create new thread
2. Else if queue not full → add to queue
3. Else if threads < maxPoolSize → create new thread
4. Else → RejectedExecutionHandler fires!
```

### Sizing Formulas
```
CPU-bound tasks:  pool_size = CPU_cores + 1
I/O-bound tasks:  pool_size = CPU_cores × (1 + wait_time / cpu_time)

Example (8 cores, DB calls take 90% of time):
  pool_size = 8 × (1 + 0.9/0.1) = 8 × 10 = 80 threads

Web app rule of thumb: Start 10-20, monitor, adjust
```

### Rejection Policies
| Policy | Behaviour | Use Case |
|--------|-----------|----------|
| AbortPolicy (default) | Throw RejectedExecutionException | Fail fast, catch in caller |
| **CallerRunsPolicy** | Caller thread executes the task | **Production — natural backpressure** |
| DiscardPolicy | Silently drop task | Non-critical analytics |
| DiscardOldestPolicy | Drop oldest queued task | Real-time feeds (latest wins) |
| Custom | Log + DLQ + alert | Production monitoring |

### @Async Rules
1. Must be on **public** method
2. Must be called from a **different bean** (Spring proxy required)
3. Return `void` or `CompletableFuture<T>`
4. Exceptions on `void` methods caught by `AsyncUncaughtExceptionHandler`

```java
// WRONG — same class, bypasses proxy
public void outer() {
    this.inner();  // NOT async!
}
@Async
public void inner() { ... }

// RIGHT — inject self or use separate bean
@Autowired private MyService self;
public void outer() {
    self.inner();  // Goes through proxy → async!
}
```

### CompletableFuture Patterns

#### Sequential Pipeline
```java
CompletableFuture.supplyAsync(() -> validate(input), executor)
    .thenApplyAsync(validated -> transform(validated), executor)
    .thenApplyAsync(transformed -> save(transformed), executor);
// Each step waits for previous, runs in thread pool
```

#### Parallel Fan-out
```java
CompletableFuture<Void> email = sendEmailAsync(orderId);
CompletableFuture<Void> inventory = updateInventoryAsync(orderId);
CompletableFuture<Void> report = generateReportAsync(orderId);

CompletableFuture.allOf(email, inventory, report)
    .thenRun(() -> log.info("All done!"));
// All 3 run in parallel, wait for all to finish
```

#### Race (First to Complete)
```java
CompletableFuture<String> provider1 = getPriceAsync("warehouse1");
CompletableFuture<String> provider2 = getPriceAsync("warehouse2");
CompletableFuture<String> provider3 = getPriceAsync("warehouse3");

CompletableFuture.anyOf(provider1, provider2, provider3)
    .thenApply(result -> (String) result);
// Returns fastest response
```

#### Exception Handling
```java
CompletableFuture.supplyAsync(() -> riskyOperation())
    .exceptionally(ex -> {
        log.error("Failed", ex);
        return fallbackValue;  // Recover
    })
    .handle((result, ex) -> {
        // Called for both success and failure
        if (ex != null) return handleError(ex);
        return result;
    });
```

#### Timeout
```java
CompletableFuture.supplyAsync(() -> slowOperation())
    .orTimeout(2, TimeUnit.SECONDS)  // Java 9+
    .exceptionally(ex -> {
        if (ex instanceof TimeoutException) return cachedValue;
        throw new CompletionException(ex);
    });
```

---

## 🛠️ Implementation

### AsyncConfig — Multiple Pools
```java
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(50);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("async-default-");
        exec.setKeepAliveSeconds(60);
        exec.setAllowCoreThreadTimeOut(true);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.setRejectedExecutionHandler((r, e) -> {
            log.error("Task rejected! queue={}, active={}", e.getQueue().size(), e.getActiveCount());
            if (!e.isShutdown()) r.run();  // CallerRunsPolicy with logging
        });
        exec.setTaskDecorator(new MDCTaskDecorator());  // MDC propagation!
        exec.initialize();
        return exec;
    }

    @Bean(name = "emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(200);  // Larger queue — less critical, can wait
        exec.setThreadNamePrefix("async-email-");
        exec.setTaskDecorator(new MDCTaskDecorator());
        exec.initialize();
        return exec;
    }

    @Bean(name = "inventoryExecutor")
    public Executor inventoryExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(15);  // More threads — high priority
        exec.setMaxPoolSize(30);
        exec.setQueueCapacity(50); // Smaller queue — fail fast if overloaded
        exec.setThreadNamePrefix("async-inventory-");
        exec.setTaskDecorator(new MDCTaskDecorator());
        exec.initialize();
        return exec;
    }

    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(3);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(500);  // Large queue — non-critical, can accumulate
        exec.setThreadNamePrefix("async-analytics-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy()); // Drop old analytics
        exec.setTaskDecorator(new MDCTaskDecorator());
        exec.initialize();
        return exec;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Async method {} threw uncaught exception: {}", method.getName(), ex.getMessage(), ex);
    }
}
```

### MDCTaskDecorator
```java
public class MDCTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) MDC.setContextMap(context);
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### AsyncOrderService
```java
@Service
public class AsyncOrderServiceImpl implements AsyncOrderService {

    @Async("taskExecutor")
    public CompletableFuture<OrderResponse> processOrderAsync(OrderRequest request) {
        log.info("Processing async: thread={}", Thread.currentThread().getName());
        // correlationId is here! MDCTaskDecorator propagated it
        OrderResponse response = orderService.createOrder(request);
        return CompletableFuture.completedFuture(response);
    }

    @Async("emailExecutor")
    public CompletableFuture<Void> sendConfirmationEmailAsync(Long orderId) {
        log.info("Sending email: orderId={}, thread={}", orderId, Thread.currentThread().getName());
        // Uses email pool — won't starve order processing
        Thread.sleep(500);
        return CompletableFuture.completedFuture(null);
    }

    @Async("inventoryExecutor")
    public CompletableFuture<Void> updateInventoryAsync(Long orderId) {
        // High-priority pool
        return CompletableFuture.completedFuture(null);
    }

    @Async("analyticsExecutor")
    public CompletableFuture<Void> generateReportAsync(Long orderId) {
        // Low-priority pool — tasks may be discarded if overloaded
        return CompletableFuture.completedFuture(null);
    }
}
```

### Thread Pool Monitoring
```java
@Service
public class ThreadPoolMonitoringService {

    public Map<String, Object> getMetrics(ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        return Map.of(
            "corePoolSize", pool.getCorePoolSize(),
            "maxPoolSize", pool.getMaximumPoolSize(),
            "activeThreads", pool.getActiveCount(),
            "poolSize", pool.getPoolSize(),
            "queueSize", pool.getQueue().size(),
            "completedTasks", pool.getCompletedTaskCount(),
            "utilizationPercent", (double) pool.getActiveCount() / pool.getMaximumPoolSize() * 100
        );
    }

    public String checkHealth(ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        double util = (double) pool.getActiveCount() / pool.getMaximumPoolSize() * 100;
        double qUtil = (double) pool.getQueue().size() / executor.getQueueCapacity() * 100;
        if (util > 90 && qUtil > 90) return "CRITICAL";
        if (util > 80 || qUtil > 80) return "WARNING";
        return "HEALTHY";
    }
}
```

---

## 🧪 Testing

```bash
# Async order creation (returns CompletableFuture)
curl -X POST http://localhost:8080/api/v1/async/orders \
  -H "X-Correlation-ID: ASYNC-001" \
  -d '{"customerName":"Alice","customerEmail":"alice@x.com","items":[...]}'

# Console — notice thread names:
# [http-nio-exec-1] [ASYNC-001] AsyncTestController - Async request received
# [async-default-1] [ASYNC-001] AsyncOrderServiceImpl - Processing: thread=async-default-1
# MDC propagated: ASYNC-001 is in both threads!

# Parallel order operations (email + inventory + report run simultaneously)
curl -X POST http://localhost:8080/api/v1/async/orders/1/complete

# Console:
# [async-email-1]     Sending email: orderId=1
# [async-inventory-1] Updating inventory: orderId=1
# [async-analytics-1] Generating report: orderId=1
# All 3 running in parallel, different pools!

# Thread pool metrics
curl http://localhost:8080/api/v1/async/metrics
# {"default":{"activeThreads":3,"utilizationPercent":6.0,...}}

# Thread pool health
curl http://localhost:8080/api/v1/async/health
# {"default":"HEALTHY","email":"HEALTHY","inventory":"HEALTHY","analytics":"HEALTHY"}
```

---

## ✅ Completion Checklist
- [ ] AsyncConfig with @EnableAsync implements AsyncConfigurer
- [ ] taskExecutor (default): 10/50/100
- [ ] emailExecutor: 5/10/200
- [ ] inventoryExecutor: 15/30/50
- [ ] analyticsExecutor: 3/10/500
- [ ] All executors: waitForTasksToCompleteOnShutdown=true
- [ ] All executors: MDCTaskDecorator applied
- [ ] Custom rejection handler with logging + CallerRunsPolicy fallback
- [ ] AsyncUncaughtExceptionHandler implemented
- [ ] MDCTaskDecorator propagates context to async thread
- [ ] AsyncOrderServiceImpl with @Async qualifier per method
- [ ] CompletableFuture: sequential pipeline example
- [ ] CompletableFuture: allOf parallel example
- [ ] CompletableFuture: anyOf race example
- [ ] CompletableFuture: exceptionally error handling
- [ ] CompletableFuture: orTimeout
- [ ] ThreadPoolMonitoringService
- [ ] AsyncTestController: /async/metrics, /async/health, /async/orders
- [ ] MDC correlationId present in async thread logs
- [ ] Different thread names visible (async-default-N, async-email-N)

---

## 💬 Interview Q&A

**Q: Why use a thread pool instead of creating threads per request?**
A: Thread creation costs ~1–2ms and ~1MB memory. Under load (1000 concurrent requests), that's 2s overhead and 1GB RAM just for threads, plus context-switching chaos. Thread pools pre-create threads, reuse them, and queue tasks efficiently. Same as your Node.js worker pool concept.

**Q: Explain core vs max pool size and when threads are created.**
A: Threads created on-demand up to corePoolSize. Beyond core, tasks go to queue. Only when queue is full are threads created above core (up to max). Threads above core timeout after keepAliveSeconds. This prevents thundering herd while allowing burst capacity.

**Q: What's the danger of unbounded queues?**
A: LinkedBlockingQueue() with no capacity → queue grows infinitely → OutOfMemoryError under sustained load. Always use bounded queues. When full, rejection policy activates — which is the correct signal that the system is overloaded.

**Q: What is CallerRunsPolicy and why is it good for production?**
A: When pool is saturated, the submitting thread executes the task itself. This provides natural backpressure — slows down the caller (HTTP thread), which reduces incoming request rate. No task is lost, and the system degrades gracefully rather than throwing exceptions.

**Q: Why can't you call @Async on the same class?**
A: Spring wraps @Async beans in a proxy. Internal method calls (this.method()) bypass the proxy and call the real object directly — no async execution. Solution: inject self via @Autowired, or refactor to a separate bean.

**Q: How do you propagate MDC context to async threads?**
A: Implement TaskDecorator, capture MDC.getCopyOfContextMap() before task submission, restore it in the async thread, clear in finally. Apply via executor.setTaskDecorator(). Without this, correlation IDs disappear from async logs.

**Q: CompletableFuture.allOf vs anyOf?**
A: allOf waits for ALL futures (parallel operations that all must succeed — email + inventory + report). anyOf returns when ANY future completes (race — multiple price providers, fastest wins). allOf returns CompletableFuture<Void>, so .join() individual futures for results.

**Q: How do you size different pools for different task types?**
A: CPU-bound tasks: cores+1 threads, small queue. I/O-bound tasks: more threads, larger queue. High-priority tasks: dedicated pool to prevent starvation. Non-critical tasks: shared pool with discard policy. Never share a pool between different priority task types.

---

## 🔗 Next Task
**Task 9: Scheduled Tasks (@Scheduled, Quartz)** — cron expressions, fixedRate vs fixedDelay, custom thread pool for scheduler, distributed scheduling concerns.
