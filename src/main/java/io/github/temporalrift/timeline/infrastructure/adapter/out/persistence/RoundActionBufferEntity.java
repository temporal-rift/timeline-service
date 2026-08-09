package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;

@Entity
@Table(name = "round_action_buffer")
class RoundActionBufferEntity extends RoundScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ActionKind kind;

    @Column(name = "card_type")
    private String cardType;

    @Column(name = "special_action")
    private String specialAction;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "card_instance_id")
    private UUID cardInstanceId;

    @Column(name = "target_event_id")
    private UUID targetEventId;

    @Column(name = "source_outcome_id")
    private UUID sourceOutcomeId;

    @Column(name = "target_outcome_id")
    private UUID targetOutcomeId;

    @Column(name = "target_player_id")
    private UUID targetPlayerId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "envelope_event_id", nullable = false)
    private UUID envelopeEventId;

    protected RoundActionBufferEntity() {
        // for JPA
    }

    RoundActionBufferEntity(
            RoundKey key,
            ActionKind kind,
            String cardType,
            String specialAction,
            UUID playerId,
            UUID cardInstanceId,
            UUID targetEventId,
            UUID sourceOutcomeId,
            UUID targetOutcomeId,
            UUID targetPlayerId,
            Instant occurredAt,
            UUID envelopeEventId) {
        super(key);
        this.kind = kind;
        this.cardType = cardType;
        this.specialAction = specialAction;
        this.playerId = playerId;
        this.cardInstanceId = cardInstanceId;
        this.targetEventId = targetEventId;
        this.sourceOutcomeId = sourceOutcomeId;
        this.targetOutcomeId = targetOutcomeId;
        this.targetPlayerId = targetPlayerId;
        this.occurredAt = occurredAt;
        this.envelopeEventId = envelopeEventId;
    }

    ActionKind kind() {
        return kind;
    }

    String cardType() {
        return cardType;
    }

    String specialAction() {
        return specialAction;
    }

    UUID playerId() {
        return playerId;
    }

    UUID cardInstanceId() {
        return cardInstanceId;
    }

    UUID targetEventId() {
        return targetEventId;
    }

    UUID sourceOutcomeId() {
        return sourceOutcomeId;
    }

    UUID targetOutcomeId() {
        return targetOutcomeId;
    }

    UUID targetPlayerId() {
        return targetPlayerId;
    }

    Instant occurredAt() {
        return occurredAt;
    }

    UUID envelopeEventId() {
        return envelopeEventId;
    }
}
