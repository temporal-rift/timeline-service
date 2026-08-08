package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;

@Entity
@Table(name = "round_event_last_shift")
class RoundEventLastShiftEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

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

    protected RoundEventLastShiftEntity() {
        // for JPA
    }

    RoundEventLastShiftEntity(
            UUID gameId,
            int eraNumber,
            int roundNumber,
            UUID futureEventId,
            ShiftType shiftType,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            int magnitude,
            UUID snapshotOutcome1Id,
            int snapshotOutcome1Probability,
            UUID snapshotOutcome2Id,
            int snapshotOutcome2Probability,
            UUID snapshotOutcome3Id,
            int snapshotOutcome3Probability) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.roundNumber = roundNumber;
        this.futureEventId = futureEventId;
        this.shiftType = shiftType;
        this.sourceOutcomeId = sourceOutcomeId;
        this.targetOutcomeId = targetOutcomeId;
        this.magnitude = magnitude;
        this.snapshotOutcome1Id = snapshotOutcome1Id;
        this.snapshotOutcome1Probability = snapshotOutcome1Probability;
        this.snapshotOutcome2Id = snapshotOutcome2Id;
        this.snapshotOutcome2Probability = snapshotOutcome2Probability;
        this.snapshotOutcome3Id = snapshotOutcome3Id;
        this.snapshotOutcome3Probability = snapshotOutcome3Probability;
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

    UUID snapshotOutcome1Id() {
        return snapshotOutcome1Id;
    }

    int snapshotOutcome1Probability() {
        return snapshotOutcome1Probability;
    }

    UUID snapshotOutcome2Id() {
        return snapshotOutcome2Id;
    }

    int snapshotOutcome2Probability() {
        return snapshotOutcome2Probability;
    }

    UUID snapshotOutcome3Id() {
        return snapshotOutcome3Id;
    }

    int snapshotOutcome3Probability() {
        return snapshotOutcome3Probability;
    }
}
