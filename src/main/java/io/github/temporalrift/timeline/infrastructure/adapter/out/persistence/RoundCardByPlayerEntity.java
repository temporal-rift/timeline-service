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
@Table(name = "round_card_by_player")
class RoundCardByPlayerEntity extends RoundScopedEntity {

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "future_event_id", nullable = false)
    private UUID futureEventId;

    @Embedded
    private ShiftDescriptor shift;

    @Embedded
    private OutcomeSnapshotTriple snapshot;

    protected RoundCardByPlayerEntity() {
        // for JPA
    }

    RoundCardByPlayerEntity(
            RoundKey key, UUID playerId, UUID futureEventId, ShiftDescriptor shift, List<OutcomeSnapshot> snapshot) {
        super(key);
        this.playerId = playerId;
        this.futureEventId = futureEventId;
        this.shift = shift;
        this.snapshot = new OutcomeSnapshotTriple(snapshot);
    }

    UUID playerId() {
        return playerId;
    }

    UUID futureEventId() {
        return futureEventId;
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
