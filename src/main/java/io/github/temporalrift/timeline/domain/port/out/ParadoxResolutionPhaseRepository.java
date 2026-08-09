package io.github.temporalrift.timeline.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;

/** Driven port for {@link ParadoxResolutionPhase} persistence (design.md Decision 1). */
public interface ParadoxResolutionPhaseRepository {

    /**
     * Atomically creates {@code candidate} only if no phase exists yet for its {@code (gameId, eraNumber)} — an
     * insert-first, conflict-checked operation, not a check-then-save sequence (developer-notes.md §4
     * Idempotency: "Do not use an exists-then-save sequence; it is racy under concurrent delivery"). Returns the
     * phase now authoritative for that era: {@code candidate} itself when this call created it, or the
     * already-existing phase otherwise — either way, {@code created()} tells the caller which happened.
     */
    CreateResult createIfAbsent(ParadoxResolutionPhase candidate);

    /** Persists a status/content update to an already-persisted phase (e.g. marking it {@code COMPLETED}). */
    ParadoxResolutionPhase save(ParadoxResolutionPhase phase);

    /** Row-level write lock, mirroring {@code ActionRoundSagaRepository}'s close-time locking convention. */
    Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId);

    /** Phases still {@code WAITING} whose {@code timerExpiresAt} has passed — the sweep's recovery query. */
    List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline);

    record CreateResult(ParadoxResolutionPhase phase, boolean created) {}
}
