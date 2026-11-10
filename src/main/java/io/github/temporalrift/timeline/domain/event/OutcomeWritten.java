package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Internal, event-sourced fact — never published. A Prophet's first non-cancelled Foresight on this
 * {@code FutureEvent} this era wrote {@code outcomeId} in action round {@code roundNumber}.
 */
public record OutcomeWritten(UUID eventId, UUID outcomeId, int roundNumber) {}
