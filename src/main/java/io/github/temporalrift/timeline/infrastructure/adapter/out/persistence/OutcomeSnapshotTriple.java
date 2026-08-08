package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A {@code FutureEvent}'s 3-outcome probability snapshot, stored as flat columns rather than a JSON blob (a
 * FutureEvent always has exactly 3 outcomes — GDD invariant) — shared by every round-scoped table that
 * records a pre-shift snapshot for exact undo ({@code round_event_last_shift}, {@code round_card_by_player}).
 */
@Embeddable
class OutcomeSnapshotTriple {

    @Column(name = "snapshot_outcome_1_id", nullable = false)
    private UUID outcome1Id;

    @Column(name = "snapshot_outcome_1_probability", nullable = false)
    private int outcome1Probability;

    @Column(name = "snapshot_outcome_2_id", nullable = false)
    private UUID outcome2Id;

    @Column(name = "snapshot_outcome_2_probability", nullable = false)
    private int outcome2Probability;

    @Column(name = "snapshot_outcome_3_id", nullable = false)
    private UUID outcome3Id;

    @Column(name = "snapshot_outcome_3_probability", nullable = false)
    private int outcome3Probability;

    protected OutcomeSnapshotTriple() {
        // for JPA
    }

    /** {@code snapshot} must have exactly 3 entries — a FutureEvent always has exactly 3 outcomes. */
    OutcomeSnapshotTriple(List<OutcomeSnapshot> snapshot) {
        this.outcome1Id = snapshot.get(0).outcomeId();
        this.outcome1Probability = snapshot.get(0).probability();
        this.outcome2Id = snapshot.get(1).outcomeId();
        this.outcome2Probability = snapshot.get(1).probability();
        this.outcome3Id = snapshot.get(2).outcomeId();
        this.outcome3Probability = snapshot.get(2).probability();
    }

    List<OutcomeSnapshot> toList() {
        return List.of(
                new OutcomeSnapshot(outcome1Id, outcome1Probability),
                new OutcomeSnapshot(outcome2Id, outcome2Probability),
                new OutcomeSnapshot(outcome3Id, outcome3Probability));
    }

    record OutcomeSnapshot(UUID outcomeId, int probability) {}
}
