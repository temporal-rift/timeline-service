package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RoundLastCardJpaRepository extends JpaRepository<RoundLastCardEntity, UUID> {

    Optional<RoundLastCardEntity> findByGameIdAndEraNumberAndRoundNumber(UUID gameId, int eraNumber, int roundNumber);

    @Modifying
    @Query("delete from RoundLastCardEntity e where e.gameId = :gameId and e.eraNumber = :eraNumber "
            + "and e.roundNumber = :roundNumber")
    void deleteByGameIdAndEraNumberAndRoundNumber(UUID gameId, int eraNumber, int roundNumber);
}
