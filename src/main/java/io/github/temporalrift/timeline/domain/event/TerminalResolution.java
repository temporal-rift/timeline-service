package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * One event's terminal state within an {@code EraResolutionCompleted} (event-schema.md §3.4).
 * {@code winningOutcomeId} is populated for {@code OUTCOME_APPLIED} and null for {@code CASCADED} —
 * matching this codebase's flat-record-with-nullable-field convention (design.md Decision 2).
 */
public record TerminalResolution(UUID eventId, int revealIndex, TerminalState terminalState, UUID winningOutcomeId) {

    public enum TerminalState {
        OUTCOME_APPLIED,
        CASCADED
    }
}
