package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * One event's terminal state within an {@code EraResolutionCompleted} (event-schema.md §3.4).
 * {@code winningOutcomeId} is populated for {@code OUTCOME_APPLIED} and null for {@code CASCADED}/{@code STALLED} —
 * matching this codebase's flat-record-with-nullable-field convention (design.md Decision 2). {@code STALLED} is
 * distinct from {@code CASCADED}: a {@code STALL} card is an ordinary disruption effect, not a paradox, and must
 * not feed game-service's paradox-collapse counter the way a cascaded event does.
 */
public record TerminalResolution(UUID eventId, int revealIndex, TerminalState terminalState, UUID winningOutcomeId) {

    public enum TerminalState {
        OUTCOME_APPLIED,
        CASCADED,
        STALLED
    }
}
