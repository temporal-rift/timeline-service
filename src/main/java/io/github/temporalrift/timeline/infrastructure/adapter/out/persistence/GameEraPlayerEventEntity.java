package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** Shared id/gameId/eraNumber/playerId/eventId columns for the game-era player-event tables. */
@MappedSuperclass
abstract class GameEraPlayerEventEntity {

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

    protected GameEraPlayerEventEntity() {
        // for JPA
    }

    GameEraPlayerEventEntity(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.playerId = playerId;
        this.eventId = eventId;
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
}
