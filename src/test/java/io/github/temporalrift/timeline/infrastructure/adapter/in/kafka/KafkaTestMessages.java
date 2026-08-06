package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/** Builds {@code game.events}-shaped test messages: real headers, no body envelope wrapper. */
final class KafkaTestMessages {

    private KafkaTestMessages() {}

    static Message<Object> withHeaders(Object payload, UUID eventId, String eventType, Integer version) {
        return MessageBuilder.withPayload(payload)
                .setHeader("eventId", eventId == null ? null : eventId.toString())
                .setHeader("aggregateId", UUID.randomUUID().toString())
                .setHeader("aggregateType", "Game")
                .setHeader("gameId", UUID.randomUUID().toString())
                .setHeader("occurredAt", Instant.now())
                .setHeader("version", version)
                .setHeader("eventType", eventType)
                .build();
    }
}
