package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;

@Entity
@Table(name = "event_store")
class EventStoreEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "sequence_nr", nullable = false)
    private long sequenceNr;

    protected EventStoreEntity() {
        // for JPA
    }

    static EventStoreEntity fromDomain(StoredEvent event) {
        var entity = new EventStoreEntity();
        entity.id = event.id();
        entity.aggregateId = event.aggregateId();
        entity.aggregateType = event.aggregateType();
        entity.eventType = event.eventType();
        entity.eventVersion = event.eventVersion();
        entity.payload = event.payload();
        entity.occurredAt = event.occurredAt();
        entity.sequenceNr = event.sequenceNr();
        return entity;
    }

    StoredEvent toDomain() {
        return new StoredEvent(
                id, aggregateId, aggregateType, eventType, eventVersion, payload, occurredAt, sequenceNr);
    }
}
