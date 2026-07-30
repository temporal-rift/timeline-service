package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.Message;

/**
 * The real {@code game.events} envelope metadata, read from Kafka record headers (event-schema.md §1) —
 * not a body field. {@code bindingName} is the {@code spring.cloud.stream.sendto.destination} header the
 * generated producer sets before Spring Modulith externalizes the message; it is the only per-event-type
 * discriminator available on a shared topic (see design.md Decision 2) and is null for records that were
 * never routed through that mechanism.
 */
record GameEventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String aggregateType,
        UUID gameId,
        Instant occurredAt,
        Integer version,
        String bindingName) {

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
                headers.get(BINDING_NAME_HEADER, String.class));
    }

    private static UUID asUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
