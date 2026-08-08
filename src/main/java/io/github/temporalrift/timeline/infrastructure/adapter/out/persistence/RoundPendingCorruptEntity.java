package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "round_pending_corrupt")
class RoundPendingCorruptEntity extends RoundScopedEntity {

    @Column(name = "target_player_id", nullable = false)
    private UUID targetPlayerId;

    protected RoundPendingCorruptEntity() {
        // for JPA
    }

    RoundPendingCorruptEntity(RoundKey key, UUID targetPlayerId) {
        super(key);
        this.targetPlayerId = targetPlayerId;
    }

    UUID targetPlayerId() {
        return targetPlayerId;
    }
}
