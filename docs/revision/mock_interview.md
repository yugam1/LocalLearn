# 🎤 Mock Interview — Interleaved Round

> **How to use:** 37 questions, deliberately shuffled across every phase —
> interleaving is the point (blocked practice feels better; mixed practice
> retains better). Answer OUT LOUD in full sentences before opening anything.
> Score yourself: ✅ fluent · 🟡 got there slowly · ❌ missed. Anything 🟡/❌ →
> that doc's Retrieval Gym goes back on your schedule.
>
> Rounds: do 1–19 in one sitting, 20–37 in another. Re-shuffle by starting from
> a random number.

---

**1.** Your service is slow. Hibernate statistics show `collectionFetchCount` = 4,812 and `collectionLoadCount` = 4,800 over one request burst. Diagnose and give me two fixes with their trade-offs. *(→ 01-foundations/06)*

**2.** Walk me through exactly what happens when a method annotated `@Transactional` is called from another method in the same class. *(→ 01-foundations/05)*

**3.** A `volatile int counter` incremented by 8 threads loses ~72% of increments. Why doesn't volatile fix it, and what are the three real fixes and when each applies? *(→ 02-concurrency/03)*

**4.** Design the retry story for a Kafka consumer that sometimes gets malformed messages and sometimes hits a flaky downstream API. *(→ 04-kafka/02)*

**5.** Where should a browser SPA store its JWT, and defend your answer against both XSS and CSRF. *(→ 06-security)*

**6.** Kubernetes keeps restarting your perfectly healthy pods whenever Kafka has a blip. What was misconfigured and what's the correct setup? *(→ 07-observability, 15-devops-docker-k8s)*

**7.** `ThreadPoolTaskExecutor`: core 10, max 50, queue 100. Sixty concurrent tasks arrive. How many threads run, and why is that the most misunderstood fact about Java thread pools? *(→ 03-async-and-scheduling/01)*

**8.** Two users edit the same order simultaneously. Compare the optimistic and pessimistic handling end to end, including what each user experiences. *(→ 01-foundations/05)*

**9.** Your daily-report cron sent three emails to every customer this morning. Explain, then fix — including what the two ShedLock durations insure against. *(→ 03-async-and-scheduling/02)*

**10.** What does `@WebMvcTest` actually load, what can it prove, and why is asserting database state in one impossible by design? *(→ 05-testing)*

**11.** A plain `boolean stop` flag never stops the worker loop, but runs fine with `-Xint`. Explain the real mechanism — and why "CPU caches" is the wrong answer. *(→ 02-concurrency/02)*

**12.** DB commit succeeds, Kafka publish fails — or vice versa. Name this problem class and walk me through the pattern that solves it, including its delivery guarantee and what that forces downstream. *(→ 04-kafka/01, 10-microservices)*

---

**13.** Your cache is @Cacheable with 30-minute TTL and the update path forgot @CacheEvict. What do users experience? Then: distinguish stampede, penetration, and avalanche with one fix each. *(→ 08-caching)*

**14.** inventory-service goes slow (not down — 30s responses). Trace how that kills order-service without any order-service bug, and name the pattern that stops each stage of the contagion. *(→ 09-resilience)*

**15.** jstack shows zero BLOCKED threads. Your colleague says "no deadlock, must be something else." Give two ways they could be wrong. *(→ 02-concurrency/01, 02-concurrency/04)*

**16.** Design the migration to rename `total_amount` → `grand_total` with three pods on rolling deploys and zero downtime. *(→ 16-production-hardening, 13-database-advanced)*

**17.** Why does `@ManyToOne` need `fetch = LAZY` explicitly, and why can't you fix EAGER per-query? *(→ 01-foundations/03, 01-foundations/06)*

**18.** Explain a consumer-group rebalance: triggers, what happens to in-flight work, and two things you configure to soften it. *(→ 04-kafka/02)*

**19.** A checked exception escaped a @Transactional method and the data committed anyway. Why, what's the fix, and how does the same checked/unchecked split show up in your exception hierarchy design? *(→ 01-foundations/05, 01-foundations/02)*

**20.** Compound index on `(customer_email, status)`. Which of these use it: email-only, email+status, status-only? For the one that can't, give two proper fixes. *(→ 13-database-advanced)*

**21.** MDC correlationId vanishes inside @Async methods and Kafka consumers. Explain the root cause once, then the fix at each of the two boundaries. *(→ 01-foundations/07, 03-async-and-scheduling/01)*

**22.** Paginate orders WITH their items. Why does the naive JOIN FETCH + Pageable combination OOM in production while working in dev, and what's the standard pattern? *(→ 01-foundations/04)*

**23.** "if (stock > 0) stock--" oversells inventory. Your teammate replaces it with AtomicInteger.get() + decrementAndGet() and the bug becomes rare. Argue why that's WORSE, then give the fix for a single JVM and for two pods. *(→ 02-concurrency/03)*

**24.** Blue-green vs canary: your new version writes subtly corrupted rows. Which strategy limits the damage and why doesn't the other's "instant rollback" save you? *(→ 16-production-hardening)*

**25.** SIGTERM arrives mid-request, mid-Kafka-batch. List everything a correctly configured Spring Boot app does before exiting, and the one arithmetic rule tying Spring's timeout to Kubernetes. *(→ 16-production-hardening, 15-devops-docker-k8s)*

**26.** A worker thread runs `while (!done) { process(queue.take()); }`. `done` is `volatile`. The producer finishes, sets `done = true`, and exits — and the worker never terminates. You already know a plain `boolean` stop flag fails for a *different* reason. Explain both mechanisms, and give two fixes with the trade-off between them. *(→ 02-concurrency/02, 02-concurrency/05)*

**27.** A teammate replaces a `LinkedBlockingQueue(1000)` with an unbounded one "so we stop dropping work under load." Three weeks later the service OOMs nightly. Walk me through what actually happened, and the three options you *always* have when a producer outruns a consumer. *(→ 02-concurrency/05)*

**28.** Your consumer pool is idle overnight yet the pods sit at 100% CPU. One line of code is responsible. What is it, how would you confirm it from a thread dump, and what replaces it? *(→ 02-concurrency/05, 02-concurrency/01)*

**29.** Your pool is `core=2, max=10, queue=100`. Sixty tasks arrive at once and monitoring shows **two** active threads. Your teammate raises `maxPoolSize` to 50 and nothing changes. Explain why, and tell me which number they should actually have changed. *(→ 02-concurrency/08)*

**30.** Under a load spike, Pool A (DiscardPolicy) reports fast, healthy response times and Pool B (CallerRunsPolicy) reports latency climbing to 600ms+. Which one is actually failing, and how would you tell from metrics alone? *(→ 02-concurrency/08, 02-concurrency/05)*

**31.** `newFixedThreadPool` and `newCachedThreadPool` each have one unbounded dimension. Name both, say what each one's OOM looks like, and explain what `SynchronousQueue` has to do with the second. *(→ 02-concurrency/08, 02-concurrency/05)*

**32.** A colleague converts a hot loop to `list.parallelStream()` and an unrelated endpoint elsewhere in the service gets 8× slower. Explain the mechanism, and why no amount of tuning that endpoint will fix it. *(→ 02-concurrency/09)*

**33.** You migrate 10,000 blocking calls from a 12-thread pool to virtual threads and throughput barely moves, though the benchmark promised ~550×. The code uses `synchronized` around each call. Explain what is happening, how you would confirm it in one JVM flag, and the fix. *(→ 02-concurrency/09, 02-concurrency/04)*

**34.** A service is wedged. `jcmd Thread.print` shows 15 threads permanently stuck, and the JVM reports exactly **one** deadlock covering **two** of them. What are the other 13 doing, and why does the JVM's own detector not see them? *(→ 02-concurrency/10, 02-concurrency/04)*

**35.** Every worker in your pool is `WAITING` inside `FutureTask.awaitDone`, the queue has items, and nothing has thrown or been rejected. Name the bug, explain why it is completely silent, and give the rule that prevents it. *(→ 02-concurrency/10, 02-concurrency/08)*

**36.** For two weeks your service has occasionally answered requests with a *different* customer's data. No errors, no hang, healthy dashboards. Where do you look, and why would a thread dump, a deadlock detector and a profiler all have shown you nothing? *(→ 02-concurrency/10, 02-concurrency/06)*

**37.** A pod sits at 100% CPU. Your colleague says "it's busy, scale it up." How do you establish in two numbers whether it is doing work or burning a core at nothing — and what distinguishes a busy-wait from a livelock? *(→ 02-concurrency/10, 02-concurrency/04)*

---

## Scoring

| ✅ count | Verdict |
|---|---|
| 33–37 | Interview-ready on covered material — maintain with R4 passes only |
| 25–32 | Solid — drill the 🟡/❌ docs' gyms this week |
| < 25 | Reset R1/R2 passes on every ❌ topic before booking anything |
