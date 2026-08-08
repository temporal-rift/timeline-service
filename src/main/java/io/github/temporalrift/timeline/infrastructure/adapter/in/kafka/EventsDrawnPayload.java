package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors {@code session-event}'s {@code EventsDrawnPayload} schema (event-schema.md §3.2). */
record EventsDrawnPayload(UUID gameId, int eraNumber, List<FutureEvent> events) {

    record FutureEvent(
            UUID eventId,
            String title,
            List<Outcome> outcomes,
            @JsonProperty(required = true) CarryOverState carryOverState) {}

    record Outcome(UUID outcomeId, String description, int initialProbability) {}

    /** Whether the event was freshly drawn or carried forward from the preceding era. */
    enum CarryOverState {
        FRESH,
        CASCADED,
        STALLED
    }
}
