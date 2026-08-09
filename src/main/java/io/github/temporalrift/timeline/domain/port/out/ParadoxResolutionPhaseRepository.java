package io.github.temporalrift.timeline.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;

/** Driven port for {@link ParadoxResolutionPhase} persistence (design.md Decision 1). */
public interface ParadoxResolutionPhaseRepository {

    ParadoxResolutionPhase save(ParadoxResolutionPhase phase);

    /** At most one phase per era — used to make opening a phase idempotent by {@code (gameId, eraNumber)}. */
    Optional<ParadoxResolutionPhase> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    /** Row-level write lock, mirroring {@code ActionRoundSagaRepository}'s close-time locking convention. */
    Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId);

    /** Phases still {@code WAITING} whose {@code timerExpiresAt} has passed — the sweep's recovery query. */
    List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline);
}
