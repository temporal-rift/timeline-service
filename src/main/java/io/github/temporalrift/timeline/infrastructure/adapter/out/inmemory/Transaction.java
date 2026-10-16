package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One transaction's uncommitted writes and held row locks. Used by the thread that owns it. */
final class Transaction {

    /** Marks a row the transaction deleted, so reads skip the committed copy until commit. */
    static final Object REMOVED = new Object();

    private final Map<String, Map<Object, Object>> overlay = new LinkedHashMap<>();
    private final Set<RowKey> locks = new LinkedHashSet<>();
    private final List<Runnable> commitHooks = new ArrayList<>();
    private boolean rollbackOnly;
    private boolean completed;

    Map<Object, Object> overlay(String table) {
        return overlay.getOrDefault(table, Map.of());
    }

    Map<String, Map<Object, Object>> overlay() {
        return overlay;
    }

    void write(String table, Object key, Object value) {
        overlay.computeIfAbsent(table, name -> new LinkedHashMap<>()).put(key, value);
    }

    boolean holds(RowKey row) {
        return locks.contains(row);
    }

    void locked(RowKey row) {
        locks.add(row);
    }

    Set<RowKey> lockedRows() {
        return Collections.unmodifiableSet(locks);
    }

    void afterCommit(Runnable action) {
        commitHooks.add(action);
    }

    List<Runnable> commitHooks() {
        return commitHooks;
    }

    boolean rollbackOnly() {
        return rollbackOnly;
    }

    void markRollbackOnly() {
        rollbackOnly = true;
    }

    boolean completed() {
        return completed;
    }

    void complete() {
        completed = true;
    }
}
