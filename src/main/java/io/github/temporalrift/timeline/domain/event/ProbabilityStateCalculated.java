package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

/**
 * One era-level fact covering every {@code FutureEvent} resolved for that era (event-schema.md §3.4) —
 * not an aggregate-level event; built by the resolution use case, not event-sourced.
 */
public record ProbabilityStateCalculated(UUID gameId, int eraNumber, List<EventState> eventStates) {

    public ProbabilityStateCalculated {
        eventStates = List.copyOf(eventStates);
    }

    public record EventState(UUID eventId, List<OutcomeState> outcomes) {

        public EventState {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record OutcomeState(UUID outcomeId, int probability, boolean isAnnihilated, boolean isSealed) {}
}
