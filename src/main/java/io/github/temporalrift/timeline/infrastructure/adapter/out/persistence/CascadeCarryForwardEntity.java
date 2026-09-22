package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "cascade_carry_forward")
class CascadeCarryForwardEntity extends GameEraPlayerEventEntity {

    @Column(name = "outcome_id", nullable = false)
    private UUID outcomeId;

    protected CascadeCarryForwardEntity() {
        // for JPA
    }

    CascadeCarryForwardEntity(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        super(gameId, eraNumber, playerId, eventId);
        this.outcomeId = outcomeId;
    }

    UUID getOutcomeId() {
        return outcomeId;
    }
}
