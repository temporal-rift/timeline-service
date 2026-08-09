package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "era_players")
class EraPlayersEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "player_ids", nullable = false)
    private String playerIds;

    protected EraPlayersEntity() {
        // for JPA
    }

    EraPlayersEntity(UUID gameId, int eraNumber, String playerIds) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.playerIds = playerIds;
    }

    UUID getGameId() {
        return gameId;
    }

    int getEraNumber() {
        return eraNumber;
    }

    String getPlayerIds() {
        return playerIds;
    }
}
