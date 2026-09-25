package io.github.temporalrift.timeline.domain.futureevent;

import java.util.Collection;
import java.util.UUID;

/** Two outcomes a {@code COLLIDE} left at equal probability this era; order carries no meaning. */
public record CollidedPair(UUID firstOutcomeId, UUID secondOutcomeId) {

    boolean within(Collection<UUID> outcomeIds) {
        return outcomeIds.contains(firstOutcomeId) && outcomeIds.contains(secondOutcomeId);
    }
}
