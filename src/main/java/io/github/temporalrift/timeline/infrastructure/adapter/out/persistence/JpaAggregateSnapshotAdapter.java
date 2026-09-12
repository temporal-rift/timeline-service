package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;

@Repository
class JpaAggregateSnapshotAdapter implements AggregateSnapshotPort {

    private final AggregateSnapshotJpaRepository repository;

    JpaAggregateSnapshotAdapter(AggregateSnapshotJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(AggregateSnapshot snapshot) {
        repository.save(AggregateSnapshotEntity.fromDomain(snapshot));
    }

    @Override
    public Optional<AggregateSnapshot> findByAggregateId(UUID aggregateId) {
        return repository.findById(aggregateId).map(AggregateSnapshotEntity::toDomain);
    }
}
