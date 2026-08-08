package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.OutcomeSnapshotTriple.OutcomeSnapshot;

/**
 * Shared shift/snapshot columns and accessors for the round-scoped "what shift touched this FutureEvent"
 * tables ({@code round_event_last_shift}, {@code round_card_by_player}) — the remaining common shape once
 * each table's own identifying column (by {@code futureEventId}, or by {@code playerId}) is factored out.
 */
@MappedSuperclass
abstract class ShiftRecordEntity extends RoundScopedEntity {

    @Embedded
    private ShiftDescriptor shift;

    @Embedded
    private OutcomeSnapshotTriple snapshot;

    protected ShiftRecordEntity() {
        // for JPA
    }

    ShiftRecordEntity(RoundKey key, ShiftDescriptor shift, List<OutcomeSnapshot> snapshot) {
        super(key);
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
