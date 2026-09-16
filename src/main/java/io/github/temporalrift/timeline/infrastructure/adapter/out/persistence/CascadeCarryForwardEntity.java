package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cascade_carry_forward")
class CascadeCarryForwardEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "outcome_id", nullable = false)
    private UUID outcomeId;

    protected CascadeCarryForwardEntity() {
        // for JPA
    }

    CascadeCarryForwardEntity(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.playerId = playerId;
        this.eventId = eventId;
        this.outcomeId = outcomeId;
    }

    UUID getGameId() {
        return gameId;
    }

    int getEraNumber() {
        return eraNumber;
    }

    void setEraNumber(int eraNumber) {
        this.eraNumber = eraNumber;
    }

    UUID getPlayerId() {
        return playerId;
    }

    UUID getEventId() {
        return eventId;
    }

    UUID getOutcomeId() {
        return outcomeId;
    }
}
