package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

@Repository
class JpaEventStoreAdapter implements EventStorePort {

    private final EventStoreJpaRepository repository;

    JpaEventStoreAdapter(EventStoreJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(StoredEvent event) {
        repository.save(EventStoreEntity.fromDomain(event));
    }

    @Override
    public List<StoredEvent> readStream(UUID aggregateId) {
        return repository.findByAggregateIdOrderBySequenceNrAsc(aggregateId).stream()
                .map(EventStoreEntity::toDomain)
                .toList();
    }
}
