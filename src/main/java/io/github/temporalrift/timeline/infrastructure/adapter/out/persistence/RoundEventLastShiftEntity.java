package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.OutcomeSnapshotTriple.OutcomeSnapshot;

@Entity
@Table(name = "round_event_last_shift")
class RoundEventLastShiftEntity extends ShiftRecordEntity {

    @Column(name = "future_event_id", nullable = false)
    private UUID futureEventId;

    protected RoundEventLastShiftEntity() {
        // for JPA
    }

    RoundEventLastShiftEntity(RoundKey key, UUID futureEventId, ShiftDescriptor shift, List<OutcomeSnapshot> snapshot) {
        super(key, shift, snapshot);
        this.futureEventId = futureEventId;
    }
}
