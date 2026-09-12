package io.github.temporalrift.timeline.domain.eventstore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable snapshot of an aggregate's value state, covering the first {@code sequenceNr} events of its stream.
 * Later events load as a tail replayed onto the snapshotted state.
 */
public record AggregateSnapshot(
        UUID aggregateId, String aggregateType, String snapshotData, long sequenceNr, Instant createdAt) {

    public AggregateSnapshot {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(snapshotData, "snapshotData");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
