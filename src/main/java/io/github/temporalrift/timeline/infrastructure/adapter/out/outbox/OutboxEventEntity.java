package io.github.temporalrift.timeline.infrastructure.adapter.out.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * DB-generated monotonic relay order (Postgres {@code autoIncrement}, not a JPA
     * {@code @GeneratedValue} — that annotation only applies to the {@code @Id}). Never written by
     * Hibernate; the database default supplies it. See 003-create-outbox-events.xml.
     */
    @Column(name = "seq", nullable = false, insertable = false, updatable = false)
    private long seq;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false)
    private String headers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEventEntity() {
        // for JPA
    }

    static OutboxEventEntity pending(
            UUID id, String topic, String messageKey, String headers, String payload, Instant createdAt) {
        var entity = new OutboxEventEntity();
        entity.id = id;
        entity.topic = topic;
        entity.messageKey = messageKey;
        entity.headers = headers;
        entity.payload = payload;
        entity.status = OutboxStatus.PENDING;
        entity.createdAt = createdAt;
        return entity;
    }

    UUID id() {
        return id;
    }

    String topic() {
        return topic;
    }

    String messageKey() {
        return messageKey;
    }

    String headers() {
        return headers;
    }

    String payload() {
        return payload;
    }

    OutboxStatus status() {
        return status;
    }
}
