# 🎤 Mock Interview — Interleaved Round

> **How to use:** 25 questions, deliberately shuffled across every phase —
> interleaving is the point (blocked practice feels better; mixed practice
> retains better). Answer OUT LOUD in full sentences before opening anything.
> Score yourself: ✅ fluent · 🟡 got there slowly · ❌ missed. Anything 🟡/❌ →
> that doc's Retrieval Gym goes back on your schedule.
>
> Rounds: do 1–12 in one sitting, 13–25 in another. Re-shuffle by starting from
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

---

## Scoring

| ✅ count | Verdict |
|---|---|
| 22–25 | Interview-ready on covered material — maintain with R4 passes only |
| 17–21 | Solid — drill the 🟡/❌ docs' gyms this week |
| < 17 | Reset R1/R2 passes on every ❌ topic before booking anything |
