package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.OutcomeSnapshotTriple.OutcomeSnapshot;

@Entity
@Table(name = "round_card_by_player")
class RoundCardByPlayerEntity extends ShiftRecordEntity {

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "future_event_id", nullable = false)
    private UUID futureEventId;

    protected RoundCardByPlayerEntity() {
        // for JPA
    }

    RoundCardByPlayerEntity(
            RoundKey key, UUID playerId, UUID futureEventId, ShiftDescriptor shift, List<OutcomeSnapshot> snapshot) {
        super(key, shift, snapshot);
        this.playerId = playerId;
        this.futureEventId = futureEventId;
    }

    UUID playerId() {
        return playerId;
    }

    UUID futureEventId() {
        return futureEventId;
    }
}
