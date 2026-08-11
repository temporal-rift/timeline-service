package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

class GameEventEnvelopeTest {

    @Test
    @DisplayName("parses every header from its real wire representation — a String, not a native type")
    void from_stringHeaders_parsesAllFields() {
        var eventId = UUID.randomUUID();
        var aggregateId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var occurredAt = Instant.parse("2026-08-11T10:15:30Z");
        var message = MessageBuilder.withPayload(new Object())
                .setHeader("eventId", eventId.toString())
                .setHeader("aggregateId", aggregateId.toString())
                .setHeader("aggregateType", "Game")
                .setHeader("gameId", gameId.toString())
                .setHeader("occurredAt", occurredAt.toString())
                .setHeader("version", "1")
                .setHeader("eventType", "EraStarted")
                .build();

        var envelope = GameEventEnvelope.from(message);

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.aggregateId()).isEqualTo(aggregateId);
        assertThat(envelope.aggregateType()).isEqualTo("Game");
        assertThat(envelope.gameId()).isEqualTo(gameId);
        assertThat(envelope.occurredAt()).isEqualTo(occurredAt);
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.eventType()).isEqualTo("EraStarted");
    }

    @Test
    @DisplayName("absent headers parse as null rather than throwing")
    void from_missingHeaders_parsesAsNull() {
        var message = MessageBuilder.withPayload(new Object()).build();

        var envelope = GameEventEnvelope.from(message);

        assertThat(envelope.eventId()).isNull();
        assertThat(envelope.aggregateId()).isNull();
        assertThat(envelope.aggregateType()).isNull();
        assertThat(envelope.gameId()).isNull();
        assertThat(envelope.occurredAt()).isNull();
        assertThat(envelope.version()).isNull();
        assertThat(envelope.eventType()).isNull();
    }
}
