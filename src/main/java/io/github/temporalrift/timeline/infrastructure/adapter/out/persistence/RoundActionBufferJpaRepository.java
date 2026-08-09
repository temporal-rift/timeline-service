package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface RoundActionBufferJpaRepository extends JpaRepository<RoundActionBufferEntity, UUID> {

    List<RoundActionBufferEntity> findByGameIdAndEraNumberAndRoundNumber(UUID gameId, int eraNumber, int roundNumber);
}
