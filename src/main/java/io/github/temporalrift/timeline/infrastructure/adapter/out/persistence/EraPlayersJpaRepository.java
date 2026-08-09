package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EraPlayersJpaRepository extends JpaRepository<EraPlayersEntity, UUID> {

    Optional<EraPlayersEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);
}
