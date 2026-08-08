package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** Shared id/gameId/eraNumber/roundNumber columns for the round-scoped card-modifier coordination tables. */
@MappedSuperclass
abstract class RoundScopedEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    protected RoundScopedEntity() {
        // for JPA
    }

    RoundScopedEntity(RoundKey key) {
        this.id = UUID.randomUUID();
        this.gameId = key.gameId();
        this.eraNumber = key.eraNumber();
        this.roundNumber = key.roundNumber();
    }
}
