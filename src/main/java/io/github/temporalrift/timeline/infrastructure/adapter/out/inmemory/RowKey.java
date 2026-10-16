package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

/** Identifies one row for locking: the table and the row's key within it. */
record RowKey(String table, Object key) {}
