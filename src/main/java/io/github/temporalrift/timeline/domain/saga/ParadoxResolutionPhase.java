package io.github.temporalrift.timeline.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;

/**
 * Persisted state for one era's paradox-resolution phase (sagas.md Saga 5, design.md
 * timeline-mvp7-paradox-resolution-saga Decision 1, timeline-mvp8-paradox-completion Decision 2): one row per
 * {@code (gameId, eraNumber)}, covering every paradox detected in that era's resolution cycle behind a single
 * shared timer. {@code resolvedTerminalResolutions} carries the {@code OUTCOME_APPLIED}/{@code STALLED} entries
 * the resolution cycle already produced before any paradox was found, so the close transaction can merge them
 * with the paradoxes' terminal outcomes without re-deriving them. {@code pendingPlayerIds} and {@code submissions}
 * back the player-submission close trigger: every game player starts pending; a recorded submission moves a
 * player out of {@code pendingPlayerIds} and into {@code submissions}, and the phase closes (by either trigger)
 * once {@code pendingPlayerIds} is empty or the timer expires, whichever first.
 */
public record ParadoxResolutionPhase(
        UUID sagaId,
        UUID gameId,
        int eraNumber,
        ParadoxResolutionPhaseStatus status,
        List<PendingParadox> pendingParadoxes,
        List<TerminalResolution> resolvedTerminalResolutions,
        List<UUID> pendingPlayerIds,
        List<Submission> submissions,
        Instant timerExpiresAt) {

    public ParadoxResolutionPhase {
        pendingParadoxes = List.copyOf(pendingParadoxes);
        resolvedTerminalResolutions = List.copyOf(resolvedTerminalResolutions);
        pendingPlayerIds = List.copyOf(pendingPlayerIds);
        submissions = List.copyOf(submissions);
    }

    public ParadoxResolutionPhase withStatus(ParadoxResolutionPhaseStatus newStatus) {
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                newStatus,
                pendingParadoxes,
                resolvedTerminalResolutions,
                pendingPlayerIds,
                submissions,
                timerExpiresAt);
    }

    public ParadoxResolutionPhase complete() {
        return withStatus(ParadoxResolutionPhaseStatus.COMPLETED);
    }

    /**
     * Records {@code submission} and removes its player from {@code pendingPlayerIds} — a no-op on
     * {@code pendingPlayerIds} if that player already submitted (redelivery), matching
     * {@code ActionRoundSagaStateManager.removeFromPending}'s idempotency.
     */
    public ParadoxResolutionPhase withSubmission(Submission submission) {
        var updatedPending = pendingPlayerIds.stream()
                .filter(playerId -> !playerId.equals(submission.playerId()))
                .toList();
        var updatedSubmissions = new ArrayList<>(submissions);
        updatedSubmissions.add(submission);
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                status,
                pendingParadoxes,
                resolvedTerminalResolutions,
                updatedPending,
                updatedSubmissions,
                timerExpiresAt);
    }

    /**
     * One paradox still open in this phase, carrying the {@code revealIndex} its affected event was drawn at and
     * its originally detected {@code type} — needed at close time to tell whether re-detection on the affected
     * event still reports this same type (persists) or not (resolved), timeline-mvp8-paradox-completion Decision
     * 2.
     */
    public record PendingParadox(UUID paradoxId, ParadoxType type, UUID affectedEventId, int revealIndex) {}

    /**
     * One player's recorded resolution-card submission, not yet applied to its target {@code FutureEvent}
     * (design.md Decision 2/4) — {@code cardType} is one of the wire {@code ParadoxResolutionCardPlayed}
     * payload's values (only {@code PUSH}/{@code SUPPRESS}/{@code SWING} are applied; anything else is a no-op at
     * close, timeline-mvp8-paradox-completion Non-Goals).
     */
    public record Submission(UUID playerId, String cardType, UUID targetEventId, UUID targetOutcomeId) {}
}
