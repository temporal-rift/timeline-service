package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;

/**
 * The records the in-memory relay hands the host are the records the Kafka relay publishes: the same case, played
 * once against PostgreSQL and Kafka and once in memory, yields the same headers and payloads in the same order.
 * Only the freshly generated envelope {@code eventId} may differ.
 */
@SpringBootTest(properties = {"game.simulation.enabled=true", "spring.application.name=in-memory-parity"})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, InMemoryParityIT.RawCollector.class})
class InMemoryParityIT {

    private static final JsonMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    @TestComponent
    static class RawCollector {

        final List<Map<String, Object>> records = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = "timeline.events", groupId = "in-memory-parity-collector")
        void collect(Message<Object> message) {
            var headers = new LinkedHashMap<String, String>();
            // Spring adds its own kafka_* receive metadata; everything else is a header of the record as sent.
            message.getHeaders().forEach((name, value) -> {
                if (value instanceof String text && !name.startsWith("kafka_")) {
                    headers.put(name, text);
                }
            });
            records.add(Map.of(
                    "key", message.getHeaders().get("kafka_receivedMessageKey", String.class),
                    "headers", headers,
                    "payload", message.getPayload()));
        }
    }

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    RawCollector collector;

    @Autowired
    ConfigureSimulationExecutionUseCase configure;

    @Test
    void sameCase_publishesTheSameRecordsOnKafkaAndInMemory() {
        var gameId = UUID.randomUUID();
        var futureEventId = UUID.randomUUID();
        var outcomes = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var players = List.of(ExecutionContextTestData.PLAYER_0, ExecutionContextTestData.PLAYER_1);
        var inbound = List.of(
                GameEventRecords.eraStarted(gameId, UUID.randomUUID(), 1, players),
                GameEventRecords.threeOutcomeEventDrawn(
                        gameId,
                        UUID.randomUUID(),
                        1,
                        futureEventId,
                        outcomes.get(0),
                        50,
                        outcomes.get(1),
                        30,
                        outcomes.get(2),
                        20),
                GameEventRecords.cardPlayed(gameId, UUID.randomUUID(), 1, futureEventId, "PUSH", outcomes.get(1)),
                GameEventRecords.actionRoundClosed(gameId, UUID.randomUUID(), 1, 1),
                GameEventRecords.resolutionStarted(gameId, UUID.randomUUID(), 1));

        configure.handle(ExecutionContextTestData.context("42"));
        inbound.forEach(this::publishToKafka);
        await().atMost(Duration.ofSeconds(60)).until(() -> contains(kafkaRecords(gameId), "EraResolutionCompleted"));

        var inMemory = new CopyOnWriteArrayList<Map<String, Object>>();
        try (var host = InMemoryTimelineHost.start(inMemory::add).configureExecution()) {
            inbound.forEach(host.delivery());
            await().atMost(Duration.ofSeconds(60)).until(() -> contains(inMemory, "EraResolutionCompleted"));

            var kafka = kafkaRecords(gameId);
            assertThat(inMemory).hasSameSizeAs(kafka);
            for (var i = 0; i < kafka.size(); i++) {
                assertThat(comparable(inMemory.get(i))).as("record %d", i).isEqualTo(comparable(kafka.get(i)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void publishToKafka(Map<String, Object> event) {
        // The Kafka message converter writes the payload as JSON itself, so it is handed the parsed body, as a producer
        // does.
        var message = MessageBuilder.<Object>withPayload(JSON.readValue((byte[]) event.get("payload"), Object.class))
                .copyHeaders((Map<String, Object>) event.get("headers"))
                .setHeader(KafkaHeaders.TOPIC, event.get("topic"))
                .setHeader(KafkaHeaders.KEY, event.get("key"))
                .build();
        kafkaTemplate.send(message);
    }

    private List<Map<String, Object>> kafkaRecords(UUID gameId) {
        return collector.records.stream()
                .filter(event -> gameId.toString().equals(headers(event).get("gameId")))
                .toList();
    }

    private static boolean contains(List<Map<String, Object>> records, String eventType) {
        return records.stream()
                .anyMatch(event -> eventType.equals(headers(event).get("eventType")));
    }

    /** Everything the consumer sees of a record except the envelope id generated afresh for each run
     * and the trace header the Kafka client adds. */
    private static Map<String, Object> comparable(Map<String, Object> event) {
        var headers = new LinkedHashMap<>(headers(event));
        headers.remove("eventId");
        headers.remove("traceparent"); // added by the Kafka client's tracing, not by the outbox row
        // A listener may be handed the value as bytes or already parsed; the sink always receives the bytes.
        var payload = event.get("payload");
        JsonNode body = payload instanceof byte[] bytes ? JSON.readTree(bytes) : JSON.valueToTree(payload);
        return Map.of("key", event.get("key"), "headers", headers, "payload", body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Map<String, Object> event) {
        return (Map<String, String>) event.get("headers");
    }
}
