package io.github.temporalrift.timeline.domain.port.out;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Outbound envelope metadata for a published {@code timeline.events} fact (event-schema.md §1). */
public record TimelineEventEnvelope<T>(
        UUID eventId, UUID aggregateId, String aggregateType, UUID gameId, Instant occurredAt, int version, T payload) {

    public static final int SCHEMA_VERSION_V1 = 1;

    public static <T> TimelineEventEnvelope<T> create(
            UUID aggregateId, String aggregateType, UUID gameId, int version, T payload, Clock clock) {
        return new TimelineEventEnvelope<>(
                UUID.randomUUID(), aggregateId, aggregateType, gameId, clock.instant(), version, payload);
    }
}
