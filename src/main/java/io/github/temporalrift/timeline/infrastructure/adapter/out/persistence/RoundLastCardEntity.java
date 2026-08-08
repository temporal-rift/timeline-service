package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard.EffectKind;

@Entity
@Table(name = "round_last_card")
class RoundLastCardEntity extends RoundScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_kind", nullable = false)
    private EffectKind effectKind;

    @Column(name = "future_event_id")
    private UUID futureEventId;

    protected RoundLastCardEntity() {
        // for JPA
    }

    RoundLastCardEntity(RoundKey key, EffectKind effectKind, UUID futureEventId) {
        super(key);
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
