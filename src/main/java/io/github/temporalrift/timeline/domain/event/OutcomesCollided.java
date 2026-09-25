package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/**
 * Event-sourced fact: a {@code COLLIDE} left its two named outcomes of one {@code FutureEvent} at equal probability.
 * The pair stays marked for the rest of the era as Dead Heat's trigger input.
 */
public record OutcomesCollided(UUID eventId, List<Outcome> outcomes, UUID firstOutcomeId, UUID secondOutcomeId) {

    public OutcomesCollided {
        outcomes = List.copyOf(outcomes);
    }
}
