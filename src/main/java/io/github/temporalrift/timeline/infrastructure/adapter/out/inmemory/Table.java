package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.springframework.dao.DuplicateKeyException;

/**
 * One keyed table of an {@link InMemoryDatabase}. The key is the row's natural unique key, so a unique-index violation
 * is a duplicate key. Every write takes the row lock first, like an SQL {@code INSERT}, {@code UPDATE} or
 * {@code DELETE}, and uniqueness is checked only after the lock is held, so a concurrent conflicting insert waits for
 * the other transaction and then fails — or succeeds, if that one rolled back.
 */
final class Table<K, V> {

    private final InMemoryDatabase database;
    private final String name;

    Table(InMemoryDatabase database, String name) {
        this.database = database;
        this.name = name;
    }

    Optional<V> find(K key) {
        return database.run(transaction -> Optional.ofNullable(read(transaction, key)));
    }

    /** Visible rows in insertion order. */
    List<V> list() {
        return list(row -> true);
    }

    List<V> list(Predicate<? super V> filter) {
        return entries(filter).stream().map(Map.Entry::getValue).toList();
    }

    List<Map.Entry<K, V>> entries(Predicate<? super V> filter) {
        return database.run(transaction -> {
            var rows = new ArrayList<Map.Entry<K, V>>();
            database.view(transaction, name).forEach((key, value) -> {
                @SuppressWarnings("unchecked")
                var row = (V) value;
                if (filter.test(row)) {
                    @SuppressWarnings("unchecked")
                    var typedKey = (K) key;
                    rows.add(Map.entry(typedKey, row));
                }
            });
            return rows;
        });
    }

    /** Inserts or replaces the row. */
    void put(K key, V value) {
        database.run(transaction -> {
            database.lock(transaction, new RowKey(name, key));
            transaction.write(name, key, value);
            return null;
        });
    }

    /** @throws DuplicateKeyException when a row with the key is already visible once the row lock is held */
    void insert(K key, V value) {
        if (!insertIfAbsent(key, value)) {
            throw new DuplicateKeyException("Duplicate key " + key + " in " + name);
        }
    }

    /** @return whether this call inserted the row; {@code false} means one already existed and nothing changed */
    boolean insertIfAbsent(K key, V value) {
        return database.run(transaction -> {
            database.lock(transaction, new RowKey(name, key));
            if (read(transaction, key) != null) {
                return false;
            }
            transaction.write(name, key, value);
            return true;
        });
    }

    boolean remove(K key) {
        return database.run(transaction -> {
            database.lock(transaction, new RowKey(name, key));
            if (read(transaction, key) == null) {
                return false;
            }
            transaction.write(name, key, Transaction.REMOVED);
            return true;
        });
    }

    /**
     * Locks the row, waiting for any other transaction holding it, then returns its latest visible value — the
     * {@code SELECT ... FOR UPDATE} behaviour of re-reading after the wait.
     */
    Optional<V> findForUpdate(K key) {
        return database.run(transaction -> {
            database.lock(transaction, new RowKey(name, key));
            return Optional.ofNullable(read(transaction, key));
        });
    }

    /** Locks the row even when it does not exist yet, so the next insert at the key is serialised behind this one. */
    void lock(K key) {
        database.run(transaction -> {
            database.lock(transaction, new RowKey(name, key));
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private V read(Transaction transaction, K key) {
        var written = transaction.overlay(name).get(key);
        if (written != null) {
            return written == Transaction.REMOVED ? null : (V) written;
        }
        return (V) database.committedValue(name, key);
    }
}
