package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;

/** Driven port for durable per-aggregate snapshots. One row per aggregate id; saves overwrite. */
public interface AggregateSnapshotPort {

    /** Persists {@code snapshot}, replacing any snapshot already stored for its aggregate id. */
    void save(AggregateSnapshot snapshot);

    /** Returns the latest snapshot for {@code aggregateId}, or empty when none was written yet. */
    Optional<AggregateSnapshot> findByAggregateId(UUID aggregateId);
}
