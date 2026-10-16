package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;

@Component
@Profile("in-memory")
class InMemoryAggregateSnapshotAdapter implements AggregateSnapshotPort {

    private final Table<UUID, AggregateSnapshot> snapshots;

    InMemoryAggregateSnapshotAdapter(InMemoryDatabase database) {
        this.snapshots = database.table(TableNames.AGGREGATE_SNAPSHOT);
    }

    @Override
    public void save(AggregateSnapshot snapshot) {
        snapshots.put(snapshot.aggregateId(), snapshot);
    }

    @Override
    public Optional<AggregateSnapshot> findByAggregateId(UUID aggregateId) {
        return snapshots.find(aggregateId);
    }
}
