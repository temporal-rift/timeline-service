package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;

@Entity
@Table(name = "aggregate_snapshot")
class AggregateSnapshotEntity {

    @Id
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_data", nullable = false)
    private String snapshotData;

    @Column(name = "sequence_nr", nullable = false)
    private long sequenceNr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AggregateSnapshotEntity() {
        // for JPA
    }

    static AggregateSnapshotEntity fromDomain(AggregateSnapshot snapshot) {
        var entity = new AggregateSnapshotEntity();
        entity.aggregateId = snapshot.aggregateId();
        entity.aggregateType = snapshot.aggregateType();
        entity.snapshotData = snapshot.snapshotData();
        entity.sequenceNr = snapshot.sequenceNr();
        entity.createdAt = snapshot.createdAt();
        return entity;
    }

    AggregateSnapshot toDomain() {
        return new AggregateSnapshot(aggregateId, aggregateType, snapshotData, sequenceNr, createdAt);
    }
}
