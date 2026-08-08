package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RoundPendingCorruptJpaRepository extends JpaRepository<RoundPendingCorruptEntity, UUID> {

    List<RoundPendingCorruptEntity> findByGameIdAndEraNumberAndRoundNumber(UUID gameId, int eraNumber, int roundNumber);
}
