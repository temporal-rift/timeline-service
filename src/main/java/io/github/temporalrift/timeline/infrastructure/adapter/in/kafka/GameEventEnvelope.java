package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.Message;

/**
 * The real {@code game.events} envelope metadata, read from Kafka record headers (event-schema.md §1) —
 * not a body field. {@code eventType} is the stable contract discriminator.
 */
record GameEventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String aggregateType,
        UUID gameId,
        Instant occurredAt,
        Integer version,
        String eventType) {

    private static final String EVENT_TYPE_HEADER = "eventType";

    static GameEventEnvelope from(Message<?> message) {
        // Every header here travels as a plain String (DomainEventHeaders.populate calls .toString() /
        // String.valueOf() on each value before putting it on the generated producer's header map) — none
        // arrive as their native type, so all of them must be parsed rather than cast.
        var headers = message.getHeaders();
        return new GameEventEnvelope(
                asUuid(headers.get("eventId", String.class)),
                asUuid(headers.get("aggregateId", String.class)),
                headers.get("aggregateType", String.class),
                asUuid(headers.get("gameId", String.class)),
                asInstant(headers.get("occurredAt", String.class)),
                asInteger(headers.get("version", String.class)),
                headers.get(EVENT_TYPE_HEADER, String.class));
    }

    private static UUID asUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant asInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static Integer asInteger(String value) {
        return value == null ? null : Integer.valueOf(value);
    }
}
