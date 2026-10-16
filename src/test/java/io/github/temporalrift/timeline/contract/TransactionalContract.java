package io.github.temporalrift.timeline.contract;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionOperations;

/**
 * What a port contract needs from its runner to exercise commit, rollback and locking: real transactions over the
 * adapter's own storage, and a way to hold one open on another thread.
 */
public interface TransactionalContract {

    TransactionOperations transactions();

    /** Runs {@code work} in a transaction on another thread and returns once it has done its first step. */
    default <T> HeldTransaction<T> holdTransaction(Supplier<T> firstStep) {
        var started = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var result = CompletableFuture.supplyAsync(() -> transactions().execute(status -> {
            var value = firstStep.get();
            started.countDown();
            awaitUnchecked(commit);
            return value;
        }));
        awaitUnchecked(started);
        return new HeldTransaction<>(commit, result);
    }

    static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A transaction on another thread that has done its work and is waiting to be told to commit. */
    record HeldTransaction<T>(CountDownLatch release, CompletableFuture<T> result) {

        public T commit() {
            release.countDown();
            return result.orTimeout(30, TimeUnit.SECONDS).join();
        }
    }
}
