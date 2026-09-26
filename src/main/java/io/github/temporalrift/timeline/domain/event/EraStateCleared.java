package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/**
 * Internal, event-sourced fact — not published externally. A carried-forward {@code FutureEvent} re-entered
 * a new era: every outcome's sealed/annihilated flag is cleared, since these are per-era state.
 * Identity, outcome set, and probabilities are
 * unaffected — {@code outcomes} carries the same probabilities, only the flags cleared.
 */
public record EraStateCleared(UUID eventId, List<Outcome> outcomes) {

    public EraStateCleared {
        outcomes = List.copyOf(outcomes);
    }
}
