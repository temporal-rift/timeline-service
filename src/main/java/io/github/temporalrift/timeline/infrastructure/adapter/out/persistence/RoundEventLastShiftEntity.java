package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.OutcomeSnapshotTriple.OutcomeSnapshot;

@Entity
@Table(name = "round_event_last_shift")
class RoundEventLastShiftEntity extends RoundScopedEntity {

    @Column(name = "future_event_id", nullable = false)
    private UUID futureEventId;

    @Embedded
    private ShiftDescriptor shift;

    @Embedded
    private OutcomeSnapshotTriple snapshot;

    protected RoundEventLastShiftEntity() {
        // for JPA
    }

    RoundEventLastShiftEntity(RoundKey key, UUID futureEventId, ShiftDescriptor shift, List<OutcomeSnapshot> snapshot) {
        super(key);
        this.futureEventId = futureEventId;
        this.shift = shift;
        this.snapshot = new OutcomeSnapshotTriple(snapshot);
    }

    ShiftType shiftType() {
        return shift.shiftType();
    }

    UUID sourceOutcomeId() {
        return shift.sourceOutcomeId();
    }

    UUID targetOutcomeId() {
        return shift.targetOutcomeId();
    }

    int magnitude() {
        return shift.magnitude();
    }

    List<OutcomeSnapshot> snapshot() {
        return snapshot.toList();
    }
}
