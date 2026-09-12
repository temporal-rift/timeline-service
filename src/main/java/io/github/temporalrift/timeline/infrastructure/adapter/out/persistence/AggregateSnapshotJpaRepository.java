package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AggregateSnapshotJpaRepository extends JpaRepository<AggregateSnapshotEntity, UUID> {}
