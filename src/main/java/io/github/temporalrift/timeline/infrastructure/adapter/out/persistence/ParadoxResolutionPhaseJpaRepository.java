package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ParadoxResolutionPhaseJpaRepository extends JpaRepository<ParadoxResolutionPhaseEntity, UUID> {

    Optional<ParadoxResolutionPhaseEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParadoxResolutionPhaseEntity p WHERE p.sagaId = :sagaId")
    Optional<ParadoxResolutionPhaseEntity> findBySagaIdWithLock(@Param("sagaId") UUID sagaId);

    @Query("SELECT p FROM ParadoxResolutionPhaseEntity p "
            + "WHERE p.status = 'WAITING' AND p.timerExpiresAt <= :deadline ORDER BY p.timerExpiresAt")
    List<ParadoxResolutionPhaseEntity> findWaitingDueBy(@Param("deadline") Instant deadline, Pageable page);
}
