package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface RoundEventLastShiftJpaRepository extends JpaRepository<RoundEventLastShiftEntity, UUID> {

    Optional<RoundEventLastShiftEntity> findByGameIdAndEraNumberAndRoundNumberAndFutureEventId(
            UUID gameId, int eraNumber, int roundNumber, UUID futureEventId);

    @Modifying
    @Query("delete from RoundEventLastShiftEntity e where e.gameId = :gameId and e.eraNumber = :eraNumber "
            + "and e.roundNumber = :roundNumber and e.futureEventId = :futureEventId")
    void deleteByGameIdAndEraNumberAndRoundNumberAndFutureEventId(
            UUID gameId, int eraNumber, int roundNumber, UUID futureEventId);
}
