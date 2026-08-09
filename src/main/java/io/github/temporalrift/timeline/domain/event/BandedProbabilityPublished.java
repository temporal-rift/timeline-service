package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ProbabilityBand;

/**
 * The coarse public probability state (GDD §4.3) revealed once Action Round 2 closes: every active
 * {@code FutureEvent}'s outcomes banded LOW/MEDIUM/HIGH from cumulative Round 1+2 state. Not event-sourced.
 */
public record BandedProbabilityPublished(UUID gameId, int eraNumber, List<EventState> eventStates) {

    public BandedProbabilityPublished {
        eventStates = List.copyOf(eventStates);
    }

    public record EventState(UUID eventId, List<OutcomeState> outcomes) {
        public EventState {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record OutcomeState(UUID outcomeId, ProbabilityBand band) {}
}
