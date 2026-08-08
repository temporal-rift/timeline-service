package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;

/**
 * One era-level fact covering every paradox detected across that era's resolving {@code FutureEvent}s
 * (event-schema.md §3.4) — not an aggregate-level event; built by the resolution use case, not event-sourced.
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
