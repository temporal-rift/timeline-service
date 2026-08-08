package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "round_amplify_pending")
class RoundAmplifyPendingEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "pending", nullable = false)
    private boolean pending;

    protected RoundAmplifyPendingEntity() {
        // for JPA
    }

    RoundAmplifyPendingEntity(UUID gameId, int eraNumber, int roundNumber, boolean pending) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.roundNumber = roundNumber;
        this.pending = pending;
    }

    boolean pending() {
        return pending;
    }
}
