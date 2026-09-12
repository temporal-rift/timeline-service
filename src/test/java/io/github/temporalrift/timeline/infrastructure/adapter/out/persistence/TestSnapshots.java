package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;

final class TestSnapshots {

    private TestSnapshots() {}

    static AggregateSnapshot snapshot(UUID aggregateId, long sequenceNr) {
        return new AggregateSnapshot(
                aggregateId, "WeaverChain", "{\"links\":[]}", sequenceNr, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
