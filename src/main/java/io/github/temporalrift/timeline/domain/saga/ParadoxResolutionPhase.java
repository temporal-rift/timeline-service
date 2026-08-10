package io.github.temporalrift.timeline.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
 *
 * <p>{@code rosterKnown} is false when the era's roster had not been persisted yet when the phase opened —
 * {@code EraStarted} is consumed in its own consumer group, unordered against the one that opens phases, so a
 * phase can legitimately open before it lands. Such a phase carries no pending players but is not "everyone has
 * submitted": {@link #allPlayersSubmitted()} stays false until {@link #withRoster} adopts the real roster, so the
 * timer is its only close trigger in the meantime and no submission is lost.
 */
public record ParadoxResolutionPhase(
        UUID sagaId,
        UUID gameId,
        int eraNumber,
        ParadoxResolutionPhaseStatus status,
        List<PendingParadox> pendingParadoxes,
        List<TerminalResolution> resolvedTerminalResolutions,
        boolean rosterKnown,
        List<UUID> pendingPlayerIds,
        List<Submission> submissions,
        Instant timerExpiresAt) {

    public ParadoxResolutionPhase {
        pendingParadoxes = List.copyOf(pendingParadoxes);
        resolvedTerminalResolutions = List.copyOf(resolvedTerminalResolutions);
        pendingPlayerIds = List.copyOf(pendingPlayerIds);
        submissions = List.copyOf(submissions);
        if (!rosterKnown && !pendingPlayerIds.isEmpty()) {
            throw new IllegalArgumentException("a phase with an unknown roster cannot have pending players");
        }
    }

    /** A phase whose era roster was available when it opened, {@code playerIds} being every player still pending. */
    public static ParadoxResolutionPhase withKnownRoster(
            UUID sagaId,
            UUID gameId,
            int eraNumber,
            ParadoxResolutionPhaseStatus status,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions,
            List<UUID> pendingPlayerIds,
            List<Submission> submissions,
            Instant timerExpiresAt) {
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                status,
                pendingParadoxes,
                resolvedTerminalResolutions,
                true,
                pendingPlayerIds,
                submissions,
                timerExpiresAt);
    }

    /** A phase whose era roster is not available yet — see the record javadoc. */
    public static ParadoxResolutionPhase withUnknownRoster(
            UUID sagaId,
            UUID gameId,
            int eraNumber,
            ParadoxResolutionPhaseStatus status,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions,
            List<Submission> submissions,
            Instant timerExpiresAt) {
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                status,
                pendingParadoxes,
                resolvedTerminalResolutions,
                false,
                List.of(),
                submissions,
                timerExpiresAt);
    }

    /** Whether the all-submitted close trigger has been met — never true while the roster is unknown. */
    public boolean allPlayersSubmitted() {
        return rosterKnown && pendingPlayerIds.isEmpty();
    }

    /**
     * Whether a submission from {@code playerId} would be recorded: for a known roster, that the player is still
     * pending; for an unknown one, that they have not already submitted — membership cannot be checked, so the
     * recorded submissions are the only guard against applying a redelivered card twice at close.
     */
    public boolean accepts(UUID playerId) {
        return rosterKnown
                ? pendingPlayerIds.contains(playerId)
                : submissions.stream()
                        .noneMatch(submission -> submission.playerId().equals(playerId));
    }

    /**
     * Adopts {@code playerIds} as this phase's roster once it becomes available, leaving pending every player who
     * has not already submitted — from here on the phase can close on all-submitted like any other. Returns
     * {@code this} unchanged when the roster is already known.
     */
    public ParadoxResolutionPhase withRoster(List<UUID> playerIds) {
        if (rosterKnown) {
            return this;
        }
        var submittedPlayerIds = submissions.stream().map(Submission::playerId).collect(Collectors.toSet());
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                status,
                pendingParadoxes,
                resolvedTerminalResolutions,
                true,
                playerIds.stream()
                        .filter(playerId -> !submittedPlayerIds.contains(playerId))
                        .toList(),
                submissions,
                timerExpiresAt);
    }

    public ParadoxResolutionPhase withStatus(ParadoxResolutionPhaseStatus newStatus) {
        return new ParadoxResolutionPhase(
                sagaId,
                gameId,
                eraNumber,
                newStatus,
                pendingParadoxes,
                resolvedTerminalResolutions,
                rosterKnown,
                pendingPlayerIds,
                submissions,
                timerExpiresAt);
    }

    public ParadoxResolutionPhase complete() {
        return withStatus(ParadoxResolutionPhaseStatus.COMPLETED);
    }

    /**
     * Records {@code submission} and removes its player from {@code pendingPlayerIds} — a no-op (returns
     * {@code this} unchanged) when that player is not currently pending, whether because they already submitted
     * (redelivery under a new {@code eventId}, which {@code ProcessedEventPort} would not itself catch) or the
     * phase never listed them, matching {@code ActionRoundSagaStateManager.removeFromPending}'s idempotency —
     * without this guard a second submission from an already-recorded player would be appended to
     * {@code submissions} again and applied a second time at close ({@link #accepts}).
     */
    public ParadoxResolutionPhase withSubmission(Submission submission) {
        if (!accepts(submission.playerId())) {
            return this;
        }
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
                rosterKnown,
                updatedPending,
                updatedSubmissions,
                timerExpiresAt);
    }

    /**
     * One paradox still open in this phase, carrying the {@code revealIndex} its affected event was drawn at and
     * its originally detected {@code type} plus {@code affectedOutcomeIds} — needed at close time to tell whether
     * re-detection on the affected event still reports this same finding (persists) or not (resolved),
     * timeline-mvp8-paradox-completion Decision 2. {@code affectedOutcomeIds} disambiguates two findings of the
     * same {@code type} on one event (e.g. two independently annihilated outcomes each tripping
     * {@code IMPOSSIBLE_ERASURE}) — matching on {@code type} alone would treat clearing either one as clearing
     * both.
     */
    public record PendingParadox(
            UUID paradoxId, ParadoxType type, List<UUID> affectedOutcomeIds, UUID affectedEventId, int revealIndex) {

        public PendingParadox {
            affectedOutcomeIds = List.copyOf(affectedOutcomeIds);
        }
    }

    /**
     * One player's recorded resolution-card submission, not yet applied to its target {@code FutureEvent}
     * (design.md Decision 2/4) — {@code cardType} is one of the wire {@code ParadoxResolutionCardPlayed}
     * payload's values (only {@code PUSH}/{@code SUPPRESS}/{@code SWING} are applied; anything else is a no-op at
     * close, timeline-mvp8-paradox-completion Non-Goals).
     */
    public record Submission(UUID playerId, String cardType, UUID targetEventId, UUID targetOutcomeId) {}
}
