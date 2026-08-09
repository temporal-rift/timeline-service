package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ParadoxResolutionPhaseJpaRepository extends JpaRepository<ParadoxResolutionPhaseEntity, UUID> {

    Optional<ParadoxResolutionPhaseEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    /**
     * Atomic create-if-absent: an {@code ON CONFLICT DO NOTHING} insert against
     * {@code ux_paradox_resolution_phase_game_era}, not a check-then-save sequence (developer-notes.md §4
     * Idempotency) — two concurrent callers for the same {@code (gameId, eraNumber)} both succeed, but only one's
     * row is ever written; the caller distinguishes which by the returned row count.
     *
     * @return 1 if this call inserted the row, 0 if a row for {@code (gameId, eraNumber)} already existed
     */
    @Modifying
    @Query(value = """
                    INSERT INTO paradox_resolution_phase
                        (saga_id, game_id, era_number, status, pending_paradoxes,
                         resolved_terminal_resolutions, pending_player_ids, submissions, timer_expires_at)
                    VALUES
                        (:sagaId, :gameId, :eraNumber, :status, CAST(:pendingParadoxes AS JSONB),
                         CAST(:resolvedTerminalResolutions AS JSONB), CAST(:pendingPlayerIds AS JSONB),
                         CAST(:submissions AS JSONB), :timerExpiresAt)
                    ON CONFLICT (game_id, era_number) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("sagaId") UUID sagaId,
            @Param("gameId") UUID gameId,
            @Param("eraNumber") int eraNumber,
            @Param("status") String status,
            @Param("pendingParadoxes") String pendingParadoxes,
            @Param("resolvedTerminalResolutions") String resolvedTerminalResolutions,
            @Param("pendingPlayerIds") String pendingPlayerIds,
            @Param("submissions") String submissions,
            @Param("timerExpiresAt") Instant timerExpiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParadoxResolutionPhaseEntity p WHERE p.sagaId = :sagaId")
    Optional<ParadoxResolutionPhaseEntity> findBySagaIdWithLock(@Param("sagaId") UUID sagaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParadoxResolutionPhaseEntity p WHERE p.gameId = :gameId AND p.eraNumber = :eraNumber")
    Optional<ParadoxResolutionPhaseEntity> findByGameIdAndEraNumberWithLock(
            @Param("gameId") UUID gameId, @Param("eraNumber") int eraNumber);

    @Query("SELECT p FROM ParadoxResolutionPhaseEntity p "
            + "WHERE p.status = 'WAITING' AND p.timerExpiresAt <= :deadline ORDER BY p.timerExpiresAt")
    List<ParadoxResolutionPhaseEntity> findWaitingDueBy(@Param("deadline") Instant deadline, Pageable page);
}
