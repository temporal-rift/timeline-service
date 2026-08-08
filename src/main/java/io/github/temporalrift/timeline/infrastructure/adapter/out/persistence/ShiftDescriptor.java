package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;

/**
 * The shift-shape columns shared by every round-scoped "what shift touched this FutureEvent" table
 * ({@code round_event_last_shift}, {@code round_card_by_player}) — extracted to eliminate duplicated column
 * mappings between them and to keep each entity's constructor under Sonar's 7-parameter limit (S107).
 */
@Embeddable
class ShiftDescriptor {

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false)
    private ShiftType shiftType;

    /** Populated for {@code SWING} only. */
    @Column(name = "source_outcome_id")
    private UUID sourceOutcomeId;

    @Column(name = "target_outcome_id", nullable = false)
    private UUID targetOutcomeId;

    /** The actually-applied value (post-AMPLIFY-doubling if applicable). */
    @Column(name = "magnitude", nullable = false)
    private int magnitude;

    protected ShiftDescriptor() {
        // for JPA
    }

    ShiftDescriptor(ShiftType shiftType, UUID sourceOutcomeId, UUID targetOutcomeId, int magnitude) {
        this.shiftType = shiftType;
        this.sourceOutcomeId = sourceOutcomeId;
        this.targetOutcomeId = targetOutcomeId;
        this.magnitude = magnitude;
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
}
