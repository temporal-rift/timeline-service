package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;

@Entity
@Table(name = "round_card_by_player")
class RoundCardByPlayerEntity extends RoundScopedEntity {

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "future_event_id", nullable = false)
    private UUID futureEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false)
    private ShiftType shiftType;

    @Column(name = "source_outcome_id")
    private UUID sourceOutcomeId;

    @Column(name = "target_outcome_id", nullable = false)
    private UUID targetOutcomeId;

    @Column(name = "magnitude", nullable = false)
    private int magnitude;

    @Column(name = "snapshot_outcome_1_id", nullable = false)
    private UUID snapshotOutcome1Id;

    @Column(name = "snapshot_outcome_1_probability", nullable = false)
    private int snapshotOutcome1Probability;

    @Column(name = "snapshot_outcome_2_id", nullable = false)
    private UUID snapshotOutcome2Id;

    @Column(name = "snapshot_outcome_2_probability", nullable = false)
    private int snapshotOutcome2Probability;

    @Column(name = "snapshot_outcome_3_id", nullable = false)
    private UUID snapshotOutcome3Id;

    @Column(name = "snapshot_outcome_3_probability", nullable = false)
    private int snapshotOutcome3Probability;

    protected RoundCardByPlayerEntity() {
        // for JPA
    }

    /** {@code snapshot} must have exactly 3 entries — a FutureEvent always has exactly 3 outcomes. */
    RoundCardByPlayerEntity(
            RoundKey key,
            UUID playerId,
            UUID futureEventId,
            ShiftType shiftType,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            int magnitude,
            List<OutcomeSnapshot> snapshot) {
        super(key);
        this.playerId = playerId;
        this.futureEventId = futureEventId;
        this.shiftType = shiftType;
        this.sourceOutcomeId = sourceOutcomeId;
        this.targetOutcomeId = targetOutcomeId;
        this.magnitude = magnitude;
        this.snapshotOutcome1Id = snapshot.get(0).outcomeId();
        this.snapshotOutcome1Probability = snapshot.get(0).probability();
        this.snapshotOutcome2Id = snapshot.get(1).outcomeId();
        this.snapshotOutcome2Probability = snapshot.get(1).probability();
        this.snapshotOutcome3Id = snapshot.get(2).outcomeId();
        this.snapshotOutcome3Probability = snapshot.get(2).probability();
    }

    UUID playerId() {
        return playerId;
    }

    UUID futureEventId() {
        return futureEventId;
    }

    ShiftType shiftType() {
        return shiftType;
    }

    UUID sourceOutcomeId() {
        return sourceOutcomeId;
    }

    UUID targetOutcomeId() {
        return targetOutcomeId;
    }

    int magnitude() {
        return magnitude;
    }

    List<OutcomeSnapshot> snapshot() {
        return List.of(
                new OutcomeSnapshot(snapshotOutcome1Id, snapshotOutcome1Probability),
                new OutcomeSnapshot(snapshotOutcome2Id, snapshotOutcome2Probability),
                new OutcomeSnapshot(snapshotOutcome3Id, snapshotOutcome3Probability));
    }

    record OutcomeSnapshot(UUID outcomeId, int probability) {}
}
