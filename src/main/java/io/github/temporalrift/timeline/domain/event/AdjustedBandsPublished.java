package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ProbabilityBand;

/**
 * The authoritative public probability state published once Action Round 2's priority-ordered replay completes:
 * every active {@code FutureEvent}'s outcomes banded LOW/MEDIUM/HIGH from the fully-applied replayed state.
 * Supersedes the game-owned {@code BandedProbabilityPublished} preview for the same game and era. Not event-sourced.
 */
public record AdjustedBandsPublished(UUID gameId, int eraNumber, List<EventState> eventStates) {

    public AdjustedBandsPublished {
        eventStates = List.copyOf(eventStates);
    }

    public record EventState(UUID eventId, List<OutcomeState> outcomes) {
        public EventState {
            outcomes = List.copyOf(outcomes);
        }
    }

    public record OutcomeState(UUID outcomeId, ProbabilityBand band) {}
}
