package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/** Event-sourced fact: a probability-shifter card changed one {@code FutureEvent}'s outcome probabilities. */
public record ProbabilityShifted(UUID eventId, List<Outcome> outcomes) {

    public ProbabilityShifted {
        outcomes = List.copyOf(outcomes);
    }
}
