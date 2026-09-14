package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 6 contract — the cache stampede.
 */
public abstract class ComputeOnceCacheContract {

    protected abstract ComputeOnceCache newCache(Function<String, String> loader);

    @Test
    @Timeout(60)
    @DisplayName("32 threads missing on the same cold key load it exactly once")
    void loadsEachKeyExactlyOnce() {
        for (int trial = 1; trial <= 20; trial++) {
            AtomicInteger loaderCalls = new AtomicInteger();
            ComputeOnceCache cache = newCache(key -> {
                loaderCalls.incrementAndGet();
                sleepQuietly();          // an expensive load widens the stampede window
                return "value-for-" + key;
            });

            int distinctKeys = 4;
            Stress.run(32, 40, i -> {
                String key = "key-" + (i % distinctKeys);
                assertThat(cache.get(key)).isEqualTo("value-for-" + key);
            });

            assertThat(cache.loadCount())
                    .as("trial %d: the loader ran %d times for %d distinct keys. When "
                        + "many threads miss on the same cold key simultaneously, they "
                        + "all pass the null check and all call the loader — in "
                        + "production that is N simultaneous calls to the service you "
                        + "were trying to protect.", trial, cache.loadCount(), distinctKeys)
                    .isEqualTo(distinctKeys);

            assertThat(loaderCalls.get())
                    .as("trial %d: the cache's own load counter disagrees with the "
                        + "loader's — that counter is not being updated atomically", trial)
                    .isEqualTo(distinctKeys);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("concurrent writes across many keys do not corrupt the map")
    void doesNotCorruptUnderConcurrentWrites() {
        AtomicInteger calls = new AtomicInteger();
        ComputeOnceCache cache = newCache(key -> {
            calls.incrementAndGet();
            return "value-for-" + key;
        });

        int distinctKeys = 5_000;
        Stress.run(8, distinctKeys, i -> {
            String key = "key-" + i;
            assertThat(cache.get(key)).isEqualTo("value-for-" + key);
        });

        assertThat(cache.loadCount())
                .as("a plain HashMap loses entries when resized concurrently, so keys "
                    + "get reloaded — and the map may be structurally corrupt besides")
                .isEqualTo(distinctKeys);
    }

    @Test
    @Timeout(30)
    @DisplayName("a repeated single-threaded get loads once")
    void cachesSingleThreaded() {
        ComputeOnceCache cache = newCache(key -> "value-for-" + key);
        assertThat(cache.get("a")).isEqualTo("value-for-a");
        assertThat(cache.get("a")).isEqualTo("value-for-a");
        assertThat(cache.get("b")).isEqualTo("value-for-b");
        assertThat(cache.loadCount()).isEqualTo(2);
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
