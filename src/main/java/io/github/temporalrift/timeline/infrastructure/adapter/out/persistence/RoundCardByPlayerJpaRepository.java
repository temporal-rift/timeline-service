package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RoundCardByPlayerJpaRepository extends JpaRepository<RoundCardByPlayerEntity, UUID> {

    Optional<RoundCardByPlayerEntity> findByGameIdAndEraNumberAndRoundNumberAndPlayerId(
            UUID gameId, int eraNumber, int roundNumber, UUID playerId);

    @Modifying
    @Query("delete from RoundCardByPlayerEntity e where e.gameId = :gameId and e.eraNumber = :eraNumber "
            + "and e.roundNumber = :roundNumber and e.playerId = :playerId")
    void deleteByGameIdAndEraNumberAndRoundNumberAndPlayerId(
            UUID gameId, int eraNumber, int roundNumber, UUID playerId);
}
