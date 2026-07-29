package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    EventStoreEntity(
            UUID id,
            UUID aggregateId,
            String aggregateType,
            String eventType,
            int eventVersion,
            String payload,
            Instant occurredAt,
            long sequenceNr) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.sequenceNr = sequenceNr;
    }

    UUID getId() {
        return id;
    }

    UUID getAggregateId() {
        return aggregateId;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getEventType() {
        return eventType;
    }

    int getEventVersion() {
        return eventVersion;
    }

    String getPayload() {
        return payload;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    long getSequenceNr() {
        return sequenceNr;
    }
}
