package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/** Event-sourced fact: a {@code SEAL} special locked one {@code FutureEvent} outcome. */
public record OutcomeSealed(UUID eventId, List<Outcome> outcomes) {

    public OutcomeSealed {
        outcomes = List.copyOf(outcomes);
    }
}
