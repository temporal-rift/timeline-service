package io.github.temporalrift.timeline.domain.eventstore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single immutable entry in the event store: one domain event appended to an aggregate's stream.
 *
 * <p>{@code payload} is the event body serialized as JSON text. The domain layer treats it as an opaque string so it
 * stays free of any serialization framework — infrastructure owns the JSON mapping.
 *
 * <p>{@code sequenceNr} is the per-aggregate monotonic position and the optimistic-concurrency guard: two appends with
 * the same {@code (aggregateId, sequenceNr)} collide on the store's unique constraint and one must retry.
 */
public record StoredEvent(
        UUID id,
        UUID aggregateId,
        String aggregateType,
        String eventType,
        int eventVersion,
        String payload,
        Instant occurredAt,
        long sequenceNr) {

    public StoredEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
