package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "game_membership")
class GameMembershipEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "faction", nullable = false)
    private String faction;

    protected GameMembershipEntity() {
        // for JPA
    }

    GameMembershipEntity(UUID gameId, UUID playerId, String faction) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.playerId = playerId;
        this.faction = faction;
    }

    UUID getId() {
        return id;
    }

    UUID getGameId() {
        return gameId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    String getFaction() {
        return faction;
    }

    void setFaction(String faction) {
        this.faction = faction;
    }
}
