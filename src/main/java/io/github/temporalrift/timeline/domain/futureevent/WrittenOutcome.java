package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/**
 * The outcome a Prophet's first non-cancelled Foresight wrote for this event this era, and the action round of that
 * Foresight. Per-era state: it clears when the event carries into another era.
 */
public record WrittenOutcome(UUID outcomeId, int roundNumber) {}
