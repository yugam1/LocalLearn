package com.ecommerce.orderservice.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pure technical demo of every {@code @Scheduled} timing model, run on the
 * custom pool from {@link com.ecommerce.orderservice.config.SchedulingConfig}.
 * See docs/phase2_task9.md, "Scheduling Types Compared".
 *
 * <p>Every method catches its own exceptions — an uncaught exception from a
 * {@code @Scheduled} method only aborts that one execution, but logging and
 * recovering explicitly is the production-safe habit (docs/phase2_task9.md,
 * "Error Handling (Critical!)").
 */
@Service
@Slf4j
public class ScheduledTaskService {

    private final AtomicInteger fixedRateCounter = new AtomicInteger(0);

    /** Measured from the START of the previous execution — can overlap if the task outruns the rate. */
    @Scheduled(fixedRate = 10000)
    public void executeFixedRate() {
        try {
            int count = fixedRateCounter.incrementAndGet();
            log.info("[FIXED-RATE] exec={}, time={}, thread={}",
                    count, LocalDateTime.now(), Thread.currentThread().getName());
            simulateWork(2000);
        } catch (Exception e) {
            log.error("[FIXED-RATE] failed, will retry next interval", e);
        }
    }

    /** Measured from the END of the previous execution — never overlaps. */
    @Scheduled(fixedDelay = 10000)
    public void executeFixedDelay() {
        try {
            log.info("[FIXED-DELAY] started: thread={}", Thread.currentThread().getName());
            simulateWork(3000);
            log.info("[FIXED-DELAY] finished: thread={}", Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("[FIXED-DELAY] failed, will retry next interval", e);
        }
    }

    /** initialDelay lets dependencies (DB pool, caches) warm up before the first run. */
    @Scheduled(initialDelay = 5000, fixedRate = 15000)
    public void executeWithInitialDelay() {
        try {
            log.info("[INITIAL-DELAY] waited 5s on startup, now running every 15s, thread={}",
                    Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("[INITIAL-DELAY] failed, will retry next interval", e);
        }
    }

    /** Cron: wall-clock driven, fires at the top of every minute. */
    @Scheduled(cron = "0 * * * * ?")
    public void executeCron() {
        try {
            log.info("[CRON] every minute: {}", LocalDateTime.now());
        } catch (Exception e) {
            log.error("[CRON] failed, will retry next interval", e);
        }
    }

    /** Demonstrates the mandatory try/catch — a real failure here must never stop future runs. */
    @Scheduled(fixedRate = 20000)
    public void executeWithErrorHandling() {
        try {
            if (Math.random() < 0.3) {
                throw new RuntimeException("Simulated transient failure");
            }
            log.info("[ERROR-HANDLING] succeeded");
        } catch (Exception e) {
            log.error("[ERROR-HANDLING] failed but will retry: {}", e.getMessage());
        }
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
