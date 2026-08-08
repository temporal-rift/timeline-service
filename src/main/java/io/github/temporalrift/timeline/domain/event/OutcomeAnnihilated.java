package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/** Event-sourced fact: an {@code ANNIHILATE} special marked one {@code FutureEvent} outcome annihilated. */
public record OutcomeAnnihilated(UUID eventId, List<Outcome> outcomes) {

    public OutcomeAnnihilated {
        outcomes = List.copyOf(outcomes);
    }
}
