package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/**
 * A paradox that persisted through its resolution phase's timer expiry (event-schema.md §3.4, GDD §6.2): the
 * affected event produces no outcome this era and carries {@code carryForwardProbabilityState} into the next
 * era's resolution. Not event-sourced, built and published by {@code ParadoxResolutionSaga}.
 */
public record ParadoxCascaded(
        UUID gameId, int eraNumber, UUID paradoxId, UUID affectedEventId, List<Outcome> carryForwardProbabilityState) {

    public ParadoxCascaded {
        carryForwardProbabilityState = List.copyOf(carryForwardProbabilityState);
    }
}
