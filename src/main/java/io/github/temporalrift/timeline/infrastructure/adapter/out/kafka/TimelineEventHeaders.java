package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.util.Map;

import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;

/** Adds the common event-envelope metadata to generated producer header maps. */
final class TimelineEventHeaders {

    private TimelineEventHeaders() {}

    static <H extends Map<String, Object>> H populate(H headers, TimelineEventEnvelope<?> event, String eventType) {
        headers.put("eventType", eventType);
        headers.put("eventId", event.eventId().toString());
        headers.put("aggregateId", event.aggregateId().toString());
        headers.put("aggregateType", event.aggregateType());
        headers.put("gameId", event.gameId().toString());
        headers.put("occurredAt", event.occurredAt());
        headers.put("version", event.version());
        return headers;
    }
}
