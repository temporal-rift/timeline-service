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
        repository.save(new EventStoreEntity(
                event.id(),
                event.aggregateId(),
                event.aggregateType(),
                event.eventType(),
                event.eventVersion(),
                event.payload(),
                event.occurredAt(),
                event.sequenceNr()));
    }

    @Override
    public List<StoredEvent> readStream(UUID aggregateId) {
        return repository.findByAggregateIdOrderBySequenceNrAsc(aggregateId).stream()
                .map(JpaEventStoreAdapter::toDomain)
                .toList();
    }

    private static StoredEvent toDomain(EventStoreEntity entity) {
        return new StoredEvent(
                entity.getId(),
                entity.getAggregateId(),
                entity.getAggregateType(),
                entity.getEventType(),
                entity.getEventVersion(),
                entity.getPayload(),
                entity.getOccurredAt(),
                entity.getSequenceNr());
    }
}
