package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CascadeCarryForwardJpaRepository extends JpaRepository<CascadeCarryForwardEntity, UUID> {

    List<CascadeCarryForwardEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    Optional<CascadeCarryForwardEntity> findByGameIdAndEraNumberAndEventIdAndOutcomeId(
            UUID gameId, int eraNumber, UUID eventId, UUID outcomeId);

    void deleteByGameId(UUID gameId);
}
