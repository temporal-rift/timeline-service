package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "grade")
    private CardGrade grade;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "card_instance_id")
    private UUID cardInstanceId;

    @Column(name = "target_event_id")
    private UUID targetEventId;

    /** JSON-encoded list of UUIDs; populated only for a list-mode SCAN. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_event_ids")
    private String targetEventIds;

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

    RoundActionBufferEntity(RoundKey key, BufferedAction action, String targetEventIds) {
        super(key);
        this.kind = action.kind();
        this.cardType = action.cardType();
        this.specialAction = action.specialAction();
        this.grade = action.grade();
        this.playerId = action.playerId();
        this.cardInstanceId = action.cardInstanceId();
        this.targetEventId = action.targetEventId();
        this.targetEventIds = targetEventIds;
        this.sourceOutcomeId = action.sourceOutcomeId();
        this.targetOutcomeId = action.targetOutcomeId();
        this.targetPlayerId = action.targetPlayerId();
        this.occurredAt = action.occurredAt();
        this.envelopeEventId = action.envelopeEventId();
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

    CardGrade grade() {
        return grade;
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

    String targetEventIds() {
        return targetEventIds;
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
