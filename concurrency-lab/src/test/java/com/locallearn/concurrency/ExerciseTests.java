package com.locallearn.concurrency;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.api.Contracts.IncidentService;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.Pipeline;
import com.locallearn.concurrency.api.Contracts.StopSignal;
import com.locallearn.concurrency.contract.BankContract;
import com.locallearn.concurrency.contract.BoundedQueueContract;
import com.locallearn.concurrency.contract.ComputeOnceCacheContract;
import com.locallearn.concurrency.contract.CounterContract;
import com.locallearn.concurrency.contract.IncidentServiceContract;
import com.locallearn.concurrency.contract.InterruptibleWorkerContract;
import com.locallearn.concurrency.contract.InventoryContract;
import com.locallearn.concurrency.contract.PipelineContract;
import com.locallearn.concurrency.contract.StopSignalContract;
import com.locallearn.concurrency.exercises.Exercises;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs every contract against YOUR implementations in {@code exercises/Exercises.java}.
 *
 * <pre>
 * ./mvnw test -Dtest=ExerciseTests          # all seven
 * ./mvnw test -Dtest=ExerciseTests$Ex1      # just exercise 1
 * </pre>
 *
 * <p>All seven fail on a fresh checkout. That is the starting line, not a
 * problem. {@link SolutionTests} runs the identical assertions against the
 * reference implementations, so if those pass and these do not, the harness is
 * fine and the bug is yours.
 */
@DisplayName("YOUR exercises")
class ExerciseTests {

    @Nested
    @DisplayName("Ex1 — lost updates")
    class Ex1 extends CounterContract {
        @Override protected Counter newCounter() {
            return new Exercises.Ex1Counter();
        }
    }

    @Nested
    @DisplayName("Ex2 — stop-flag visibility")
    class Ex2 extends StopSignalContract {
        @Override protected StopSignal newSignal() {
            return new Exercises.Ex2StopSignal();
        }
    }

    @Nested
    @DisplayName("Ex3 — check-then-act oversell")
    class Ex3 extends InventoryContract {
        @Override protected Inventory newInventory(int initialStock) {
            return new Exercises.Ex3Inventory(initialStock);
        }
    }

    @Nested
    @DisplayName("Ex4 — cooperative cancellation")
    class Ex4 extends InterruptibleWorkerContract {
        @Override protected InterruptibleWorker newWorker() {
            return new Exercises.Ex4Worker();
        }
    }

    @Nested
    @DisplayName("Ex5 — deadlock-free transfers")
    class Ex5 extends BankContract {
        @Override protected Bank newBank(int accounts, long initialBalance) {
            return new Exercises.Ex5Bank(accounts, initialBalance);
        }
    }

    @Nested
    @DisplayName("Ex6 — cache stampede")
    class Ex6 extends ComputeOnceCacheContract {
        @Override protected ComputeOnceCache newCache(Function<String, String> loader) {
            return new Exercises.Ex6Cache(loader);
        }
    }

    @Nested
    @DisplayName("Ex7 — blocking bounded queue")
    class Ex7 extends BoundedQueueContract {
        @Override protected <T> BoundedQueue<T> newQueue(int capacity) {
            return new Exercises.Ex7Queue<>(capacity);
        }
    }

    @Nested
    @DisplayName("Ex8 — backpressure & draining shutdown")
    class Ex8 extends PipelineContract {
        @Override protected Pipeline newPipeline(int capacity, int workers,
                                                 Consumer<String> processor) {
            return new Exercises.Ex8Pipeline(capacity, workers, processor);
        }
    }

    @Nested
    @DisplayName("Ex15 — the diagnostic incident")
    class Ex15 extends IncidentServiceContract {
        @Override protected IncidentService newService(int workers) {
            return new Exercises.Ex15IncidentService(workers);
        }
    }
}
