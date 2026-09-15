package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;

@TimelineServiceIntegrationTest
class GameEventsDeadLetterIT {

    private static final String DEAD_LETTER_TOPIC = "game.dlq";
    private static final String GAME_EVENTS_TOPIC = "game.events";

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    ConsumerFactory<Object, Object> consumerFactory;

    @Autowired
    EraPlayersPort eraPlayers;

    @Autowired
    GameEventsTestPublisher gameEvents;

    @Test
    void poisonRecord_isParkedAndFollowingRecordProcesses() {
        var gameId = UUID.randomUUID();
        var poisonEventId = UUID.randomUUID();
        var playerIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        try (var deadLetterConsumer = consumerFactory.createConsumer("dead-letter-it-" + UUID.randomUUID(), "")) {
            deadLetterConsumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            publishPoisonEraStarted(gameId, poisonEventId);
            gameEvents.eraStarted(gameId, 2, playerIds);

            var parked = await().atMost(Duration.ofSeconds(30))
                    .until(() -> parkedRecord(deadLetterConsumer, poisonEventId), Objects::nonNull);

            assertThat(parked.key()).isEqualTo(gameId.toString());
            assertThat(headerValue(parked, "eventId")).isEqualTo(poisonEventId.toString());
            assertThat(headerValue(parked, "eventType")).isEqualTo("EraStarted");
            assertThat(headerValue(parked, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isNotBlank();
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(eraPlayers.find(gameId, 2)).contains(playerIds));
        }
    }

    private void publishPoisonEraStarted(UUID gameId, UUID eventId) {
        var message = MessageBuilder.withPayload("invalid-era-started-payload")
                .setHeader(KafkaHeaders.TOPIC, GAME_EVENTS_TOPIC)
                .setHeader(KafkaHeaders.KEY, gameId.toString())
                .setHeader("eventId", eventId.toString())
                .setHeader("aggregateId", gameId.toString())
                .setHeader("aggregateType", "Game")
                .setHeader("gameId", gameId.toString())
                .setHeader("occurredAt", Instant.now().toString())
                .setHeader("version", "1")
                .setHeader("eventType", "EraStarted")
                .build();
        kafkaTemplate.send(message);
    }

    private static ConsumerRecord<Object, Object> parkedRecord(Consumer<Object, Object> consumer, UUID eventId) {
        var records = consumer.poll(Duration.ofMillis(500));
        return StreamSupport.stream(records.spliterator(), false)
                .filter(record -> eventId.toString().equals(headerValue(record, "eventId")))
                .findFirst()
                .orElse(null);
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
