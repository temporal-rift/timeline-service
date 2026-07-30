package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FutureEventEraIndexJpaRepository extends JpaRepository<FutureEventEraIndexEntity, UUID> {

    List<FutureEventEraIndexEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);
}
