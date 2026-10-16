package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The in-memory stand-in for the PostgreSQL database: named tables of committed rows, a write overlay per
 * transaction, and row locks held until the owning transaction completes. Isolation is read committed — a transaction
 * sees its own writes plus whatever others have committed.
 *
 * <p>All state belongs to the instance, so two application contexts in one JVM share nothing. A transaction is bound
 * to its thread under this instance as the resource key, which is what lets the Spring transaction manager suspend and
 * resume it like any other resource.
 */
public final class InMemoryDatabase {

    private static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private final ReadWriteLock commitLock = new ReentrantReadWriteLock();
    private final Map<String, Map<Object, Object>> committed = new HashMap<>();
    private final Map<RowKey, Transaction> rowOwners = new HashMap<>();
    private final Duration lockTimeout;

    public InMemoryDatabase() {
        this(DEFAULT_LOCK_TIMEOUT);
    }

    InMemoryDatabase(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    <K, V> Table<K, V> table(String name) {
        return new Table<>(this, name);
    }

    /** Runs {@code action} once the current transaction commits, or immediately when none is active. */
    void afterCommit(Runnable action) {
        var current = current();
        if (current == null) {
            action.run();
        } else {
            current.afterCommit(action);
        }
    }

    Transaction current() {
        var bound = (Transaction) TransactionSynchronizationManager.getResource(this);
        return bound == null || bound.completed() ? null : bound;
    }

    Transaction begin() {
        unbind(); // only a completed transaction can still be bound here, while its after-commit callbacks run
        var transaction = new Transaction();
        bind(transaction);
        return transaction;
    }

    void bind(Transaction transaction) {
        TransactionSynchronizationManager.bindResource(this, transaction);
    }

    Transaction unbind() {
        return TransactionSynchronizationManager.hasResource(this)
                ? (Transaction) TransactionSynchronizationManager.unbindResource(this)
                : null;
    }

    /** Applies the transaction's writes atomically to readers, then releases its locks and runs its commit hooks. */
    void commit(Transaction transaction) {
        commitLock.writeLock().lock();
        try {
            transaction.overlay().forEach((table, writes) -> {
                var rows = committed.computeIfAbsent(table, name -> new LinkedHashMap<>());
                writes.forEach((key, value) -> {
                    if (value == Transaction.REMOVED) {
                        rows.remove(key);
                    } else {
                        rows.put(key, value);
                    }
                });
            });
        } finally {
            commitLock.writeLock().unlock();
        }
        releaseLocks(transaction);
        transaction.complete();
        transaction.commitHooks().forEach(Runnable::run);
    }

    void rollback(Transaction transaction) {
        releaseLocks(transaction);
        transaction.complete();
    }

    /** Groups several table operations into one atomic step: the current transaction, or a fresh one-step one. */
    <T> T inTransaction(Supplier<T> work) {
        return run(transaction -> work.get());
    }

    /** Runs {@code operation} in the current transaction, or in a one-operation transaction when none is active. */
    <T> T run(Function<Transaction, T> operation) {
        var current = current();
        if (current != null) {
            return operation.apply(current);
        }
        // A transaction that already completed can still be bound while its after-commit callbacks run; the
        // operation then belongs to a new transaction of its own, as with a connection returned to autocommit.
        var completed = unbind();
        var implicit = begin();
        try {
            T result;
            try {
                result = operation.apply(implicit);
            } catch (RuntimeException | Error e) {
                rollback(implicit);
                throw e;
            }
            commit(implicit);
            return result;
        } finally {
            unbind();
            if (completed != null) {
                bind(completed);
            }
        }
    }

    /** The visible rows of a table, in insertion order: committed rows overlaid with the transaction's own writes. */
    Map<Object, Object> view(Transaction transaction, String table) {
        Map<Object, Object> visible;
        commitLock.readLock().lock();
        try {
            visible = new LinkedHashMap<>(committed.getOrDefault(table, Map.of()));
        } finally {
            commitLock.readLock().unlock();
        }
        transaction.overlay(table).forEach((key, value) -> {
            if (value == Transaction.REMOVED) {
                visible.remove(key);
            } else {
                visible.put(key, value);
            }
        });
        return visible;
    }

    Object committedValue(String table, Object key) {
        commitLock.readLock().lock();
        try {
            return committed.getOrDefault(table, Map.of()).get(key);
        } finally {
            commitLock.readLock().unlock();
        }
    }

    /** Takes the row lock for the transaction, waiting for another transaction that holds it. */
    void lock(Transaction transaction, RowKey row) {
        if (transaction.holds(row)) {
            return;
        }
        var deadline = System.nanoTime() + lockTimeout.toNanos();
        synchronized (rowOwners) {
            while (true) {
                var owner = rowOwners.get(row);
                if (owner == null || owner == transaction) {
                    rowOwners.put(row, transaction);
                    transaction.locked(row);
                    return;
                }
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new CannotAcquireLockException(
                            "Timed out after " + lockTimeout + " waiting for the row lock on " + row);
                }
                awaitRelease(remaining);
            }
        }
    }

    private void awaitRelease(long nanos) {
        try {
            TimeUnit.NANOSECONDS.timedWait(rowOwners, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CannotAcquireLockException("Interrupted while waiting for a row lock", e);
        }
    }

    private void releaseLocks(Transaction transaction) {
        synchronized (rowOwners) {
            transaction.lockedRows().forEach(rowOwners::remove);
            rowOwners.notifyAll();
        }
    }
}
