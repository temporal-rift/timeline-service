package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.Message;

/**
 * The real {@code game.events} envelope metadata, read from Kafka record headers (event-schema.md §1) —
 * not a body field. {@code eventType} is the stable contract discriminator. {@code bindingName} is retained
 * only to consume records published by the previous ZenWave producer implementation.
 */
record GameEventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String aggregateType,
        UUID gameId,
        Instant occurredAt,
        Integer version,
        String eventType,
        String bindingName) {

    private static final String EVENT_TYPE_HEADER = "eventType";
    private static final String BINDING_NAME_HEADER = "spring.cloud.stream.sendto.destination";

    static GameEventEnvelope from(Message<?> message) {
        // eventId/aggregateId/gameId travel as plain String headers (DomainEventHeaders.populate calls
        // .toString() on each UUID before putting it on the generated producer's header map) — not typed
        // UUID headers, so they must be parsed here rather than cast.
        var headers = message.getHeaders();
        return new GameEventEnvelope(
                asUuid(headers.get("eventId", String.class)),
                asUuid(headers.get("aggregateId", String.class)),
                headers.get("aggregateType", String.class),
                asUuid(headers.get("gameId", String.class)),
                headers.get("occurredAt", Instant.class),
                headers.get("version", Integer.class),
                headers.get(EVENT_TYPE_HEADER, String.class),
                headers.get(BINDING_NAME_HEADER, String.class));
    }

    private static UUID asUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
