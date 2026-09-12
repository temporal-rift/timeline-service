package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

@Entity
@Table(name = "weaver_chain_saga")
class WeaverChainSagaEntity {

    @Id
    @Column(name = "chain_id", nullable = false)
    private UUID chainId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WeaverChainSagaStatus status;

    @Column(name = "tapestry_protected", nullable = false)
    private boolean tapestryProtected;

    @Column(name = "tapestry_used_era")
    private Integer tapestryUsedEra;

    protected WeaverChainSagaEntity() {
        // for JPA
    }

    WeaverChainSagaEntity(
            UUID chainId,
            UUID gameId,
            UUID playerId,
            WeaverChainSagaStatus status,
            boolean tapestryProtected,
            Integer tapestryUsedEra) {
        this.chainId = chainId;
        this.gameId = gameId;
        this.playerId = playerId;
        this.status = status;
        this.tapestryProtected = tapestryProtected;
        this.tapestryUsedEra = tapestryUsedEra;
    }

    UUID getChainId() {
        return chainId;
    }

    UUID getGameId() {
        return gameId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    WeaverChainSagaStatus getStatus() {
        return status;
    }

    boolean isTapestryProtected() {
        return tapestryProtected;
    }

    Integer getTapestryUsedEra() {
        return tapestryUsedEra;
    }
}
