package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

/**
 * Opens one resolution phase covering every paradox detected in a resolution cycle
 * — not event-sourced, built and published by {@code ParadoxResolutionSaga}.
 */
public record ParadoxResolutionPhaseStarted(UUID gameId, int eraNumber, List<UUID> paradoxIds, int timerSeconds) {

    public ParadoxResolutionPhaseStarted {
        paradoxIds = List.copyOf(paradoxIds);
    }
}
