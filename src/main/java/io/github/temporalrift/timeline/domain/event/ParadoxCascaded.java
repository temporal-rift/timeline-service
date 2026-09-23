package io.github.temporalrift.timeline.domain.event;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;

/**
 * One affected event that retains paradox findings after its resolution phase. The event produces no outcome
 * this era and carries {@code carryForwardProbabilityState} into the next era. {@code paradoxId} remains the
 * first member of {@code paradoxIds} for consumers of older facts.
 */
public record ParadoxCascaded(
        UUID gameId,
        int eraNumber,
        UUID paradoxId,
        List<UUID> paradoxIds,
        UUID affectedEventId,
        List<Outcome> carryForwardProbabilityState,
        List<UUID> detonatedByPlayerIds) {

    public ParadoxCascaded {
        paradoxIds = List.copyOf(paradoxIds);
        if (paradoxIds.isEmpty()
                || !paradoxIds.getFirst().equals(paradoxId)
                || new HashSet<>(paradoxIds).size() != paradoxIds.size()) {
            throw new IllegalArgumentException("paradoxIds must be unique and start with paradoxId");
        }
        carryForwardProbabilityState = List.copyOf(carryForwardProbabilityState);
        detonatedByPlayerIds = List.copyOf(detonatedByPlayerIds);
    }
}
