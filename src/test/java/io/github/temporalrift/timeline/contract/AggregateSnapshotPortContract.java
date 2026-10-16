package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;

public interface AggregateSnapshotPortContract {

    AggregateSnapshotPort snapshots();

    @Test
    @DisplayName("snapshots: none until saved, then the latest save replaces the earlier one")
    default void snapshots_latestSaveWins() {
        var aggregateId = UUID.randomUUID();
        assertThat(snapshots().findByAggregateId(aggregateId)).isEmpty();

        var first = snapshot(aggregateId, 20, "{\"a\": 1}");
        var second = snapshot(aggregateId, 40, "{\"a\": 2}");
        snapshots().save(first);
        assertThat(snapshots().findByAggregateId(aggregateId)).contains(first);
        snapshots().save(second);

        assertThat(snapshots().findByAggregateId(aggregateId)).contains(second);
        assertThat(snapshots().findByAggregateId(UUID.randomUUID())).isEmpty();
    }

    private static AggregateSnapshot snapshot(UUID aggregateId, long sequenceNr, String data) {
        return new AggregateSnapshot(
                aggregateId, "WeaverChain", data, sequenceNr, Instant.parse("2026-03-01T10:00:00Z"));
    }
}
