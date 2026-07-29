package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EventStoreJpaRepository extends JpaRepository<EventStoreEntity, UUID> {

    List<EventStoreEntity> findByAggregateIdOrderBySequenceNrAsc(UUID aggregateId);
}
