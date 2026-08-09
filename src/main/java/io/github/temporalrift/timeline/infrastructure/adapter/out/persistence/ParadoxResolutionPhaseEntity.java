package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "paradox_resolution_phase")
class ParadoxResolutionPhaseEntity {

    @Id
    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_paradoxes", nullable = false)
    private String pendingParadoxes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_terminal_resolutions", nullable = false)
    private String resolvedTerminalResolutions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_player_ids", nullable = false)
    private String pendingPlayerIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submissions", nullable = false)
    private String submissions;

    @Column(name = "timer_expires_at", nullable = false)
    private Instant timerExpiresAt;

    protected ParadoxResolutionPhaseEntity() {
        // for JPA
    }

    UUID getSagaId() {
        return sagaId;
    }

    void setSagaId(UUID sagaId) {
        this.sagaId = sagaId;
    }

    UUID getGameId() {
        return gameId;
    }

    void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    int getEraNumber() {
        return eraNumber;
    }

    void setEraNumber(int eraNumber) {
        this.eraNumber = eraNumber;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getPendingParadoxes() {
        return pendingParadoxes;
    }

    void setPendingParadoxes(String pendingParadoxes) {
        this.pendingParadoxes = pendingParadoxes;
    }

    String getResolvedTerminalResolutions() {
        return resolvedTerminalResolutions;
    }

    void setResolvedTerminalResolutions(String resolvedTerminalResolutions) {
        this.resolvedTerminalResolutions = resolvedTerminalResolutions;
    }

    String getPendingPlayerIds() {
        return pendingPlayerIds;
    }

    void setPendingPlayerIds(String pendingPlayerIds) {
        this.pendingPlayerIds = pendingPlayerIds;
    }

    String getSubmissions() {
        return submissions;
    }

    void setSubmissions(String submissions) {
        this.submissions = submissions;
    }

    Instant getTimerExpiresAt() {
        return timerExpiresAt;
    }

    void setTimerExpiresAt(Instant timerExpiresAt) {
        this.timerExpiresAt = timerExpiresAt;
    }
}
