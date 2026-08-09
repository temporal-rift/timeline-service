package io.github.temporalrift.timeline.domain.saga;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;

/**
 * Persisted state for one era's paradox-resolution phase (sagas.md Saga 5, design.md
 * timeline-mvp7-paradox-resolution-saga Decision 1): one row per {@code (gameId, eraNumber)}, covering every
 * paradox detected in that era's resolution cycle behind a single shared timer. {@code resolvedTerminalResolutions}
 * carries the {@code OUTCOME_APPLIED}/{@code STALLED} entries the resolution cycle already produced before any
 * paradox was found, so the timer-expiry transaction can merge them with the paradoxes' terminal outcomes without
 * re-deriving them.
 */
public record ParadoxResolutionPhase(
        UUID sagaId,
        UUID gameId,
        int eraNumber,
        ParadoxResolutionPhaseStatus status,
        List<PendingParadox> pendingParadoxes,
        List<TerminalResolution> resolvedTerminalResolutions,
        Instant timerExpiresAt) {

    public ParadoxResolutionPhase {
        pendingParadoxes = List.copyOf(pendingParadoxes);
        resolvedTerminalResolutions = List.copyOf(resolvedTerminalResolutions);
    }

    public ParadoxResolutionPhase complete() {
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                ParadoxResolutionPhaseStatus.COMPLETED,
                pendingParadoxes,
                resolvedTerminalResolutions,
                timerExpiresAt);
    }

    /** One paradox still open in this phase, carrying the {@code revealIndex} its affected event was drawn at. */
    public record PendingParadox(UUID paradoxId, UUID affectedEventId, int revealIndex) {}
}
