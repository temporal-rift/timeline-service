package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "scan_entitlement")
class ScanEntitlementEntity {

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

    protected ScanEntitlementEntity() {
        // for JPA
    }

    ScanEntitlementEntity(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {
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

    UUID getPlayerId() {
        return playerId;
    }

    UUID getEventId() {
        return eventId;
    }
}
