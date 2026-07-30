package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.List;
import java.util.UUID;

/** Mirrors {@code session-event}'s {@code EventsDrawnPayload} schema (event-schema.md §3.2). */
record EventsDrawnPayload(UUID gameId, int eraNumber, List<FutureEvent> events) {

    record FutureEvent(UUID eventId, String title, List<Outcome> outcomes, boolean isCascaded) {}

    record Outcome(UUID outcomeId, String description, int initialProbability) {}
}
