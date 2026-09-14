package com.locallearn.concurrency;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.MiniPool;
import com.locallearn.concurrency.api.Contracts.Pipeline;
import com.locallearn.concurrency.api.Contracts.StopSignal;
import com.locallearn.concurrency.api.Contracts.WorkloadRunner;
import com.locallearn.concurrency.contract.BankContract;
import com.locallearn.concurrency.contract.BoundedQueueContract;
import com.locallearn.concurrency.contract.ComputeOnceCacheContract;
import com.locallearn.concurrency.contract.CounterContract;
import com.locallearn.concurrency.contract.InterruptibleWorkerContract;
import com.locallearn.concurrency.contract.InventoryContract;
import com.locallearn.concurrency.contract.MiniPoolContract;
import com.locallearn.concurrency.contract.PipelineContract;
import com.locallearn.concurrency.contract.StopSignalContract;
import com.locallearn.concurrency.contract.WorkloadRunnerContract;
import com.locallearn.concurrency.solutions.Solutions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The identical contracts run against the REFERENCE implementations.
 *
 * <p>These must always pass. They serve two purposes: they prove the test
 * harness itself is sound (so a failure in {@link ExerciseTests} is genuinely
 * your bug), and they give you a working target to diff against once you have
 * made your own attempt.
 *
 * <pre>
 * ./mvnw test -Dtest=SolutionTests
 * </pre>
 */
@DisplayName("Reference solutions")
class SolutionTests {

    @Nested
    @DisplayName("Sol1 — lost updates")
    class Sol1 extends CounterContract {
        @Override protected Counter newCounter() {
            return new Solutions.Sol1Counter();
        }
    }

    @Nested
    @DisplayName("Sol2 — stop-flag visibility")
    class Sol2 extends StopSignalContract {
        @Override protected StopSignal newSignal() {
            return new Solutions.Sol2StopSignal();
        }
    }

    @Nested
    @DisplayName("Sol3 — check-then-act oversell")
    class Sol3 extends InventoryContract {
        @Override protected Inventory newInventory(int initialStock) {
            return new Solutions.Sol3Inventory(initialStock);
        }
    }

    @Nested
    @DisplayName("Sol4 — cooperative cancellation")
    class Sol4 extends InterruptibleWorkerContract {
        @Override protected InterruptibleWorker newWorker() {
            return new Solutions.Sol4Worker();
        }
    }

    @Nested
    @DisplayName("Sol5 — deadlock-free transfers")
    class Sol5 extends BankContract {
        @Override protected Bank newBank(int accounts, long initialBalance) {
            return new Solutions.Sol5Bank(accounts, initialBalance);
        }
    }

    @Nested
    @DisplayName("Sol6 — cache stampede")
    class Sol6 extends ComputeOnceCacheContract {
        @Override protected ComputeOnceCache newCache(Function<String, String> loader) {
            return new Solutions.Sol6Cache(loader);
        }
    }

    @Nested
    @DisplayName("Sol7 — blocking bounded queue")
    class Sol7 extends BoundedQueueContract {
        @Override protected <T> BoundedQueue<T> newQueue(int capacity) {
            return new Solutions.Sol7Queue<>(capacity);
        }
    }

    @Nested
    @DisplayName("Sol8 — backpressure & draining shutdown")
    class Sol8 extends PipelineContract {
        @Override protected Pipeline newPipeline(int capacity, int workers,
                                                 Consumer<String> processor) {
            return new Solutions.Sol8Pipeline(capacity, workers, processor);
        }
    }

    @Nested
    @DisplayName("Sol13 — a thread pool built from scratch")
    class Sol13 extends MiniPoolContract {
        @Override protected MiniPool newPool(int corePoolSize, int maxPoolSize, int queueCapacity) {
            return new Solutions.Sol13Pool(corePoolSize, maxPoolSize, queueCapacity);
        }
    }

    @Nested
    @DisplayName("Sol14 — what kind of work is this?")
    class Sol14 extends WorkloadRunnerContract {
        @Override protected WorkloadRunner newRunner() {
            return new Solutions.Sol14Runner();
        }
    }
}
