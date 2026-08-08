package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard.EffectKind;

@Entity
@Table(name = "round_last_card")
class RoundLastCardEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "era_number", nullable = false)
    private int eraNumber;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_kind", nullable = false)
    private EffectKind effectKind;

    @Column(name = "future_event_id")
    private UUID futureEventId;

    protected RoundLastCardEntity() {
        // for JPA
    }

    RoundLastCardEntity(UUID gameId, int eraNumber, int roundNumber, EffectKind effectKind, UUID futureEventId) {
        this.id = UUID.randomUUID();
        this.gameId = gameId;
        this.eraNumber = eraNumber;
        this.roundNumber = roundNumber;
        this.effectKind = effectKind;
        this.futureEventId = futureEventId;
    }

    EffectKind effectKind() {
        return effectKind;
    }

    UUID futureEventId() {
        return futureEventId;
    }
}
