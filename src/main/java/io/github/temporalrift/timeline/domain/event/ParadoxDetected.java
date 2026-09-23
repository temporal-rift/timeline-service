package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;

/**
 * Announces paradox findings for an era. The resolution use case publishes its initial findings together;
 * the paradox-resolution phase may announce new findings discovered when submitted cards are applied.
 */
public record ParadoxDetected(UUID gameId, int eraNumber, List<Paradox> paradoxes) {

    public ParadoxDetected {
        paradoxes = List.copyOf(paradoxes);
    }

    public record Paradox(
            UUID paradoxId, ParadoxType type, UUID affectedEventId, List<UUID> affectedOutcomeIds, String description) {

        public Paradox {
            affectedOutcomeIds = List.copyOf(affectedOutcomeIds);
        }
    }
}
