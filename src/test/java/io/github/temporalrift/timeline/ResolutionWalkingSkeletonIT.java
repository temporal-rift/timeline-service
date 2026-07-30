package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end proof of the MVP2 walking skeleton: publishes real-shaped {@code EraStarted} /
 * {@code EventsDrawn} / {@code ResolutionStarted} messages (headers per event-schema.md §1, not the
 * hand-rolled body-envelope shape used elsewhere for {@code timeline.events} placeholders) onto
 * {@code game.events}, and asserts {@code OutcomeApplied} — preceded by {@code ProbabilityStateCalculated}
 * — lands on {@code timeline.events}. Also confirms the {@code spring.cloud.stream.sendto.destination}
 * header round-trips end-to-end (design.md Decision 2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class})
class ResolutionWalkingSkeletonIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String PROBABILITY_STATE_CALCULATED_BINDING =
            "Timelinepublish-probability-state-calculated-out";
    private static final String OUTCOME_APPLIED_BINDING = "Timelinepublish-outcome-applied-out";

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCollector() {
        // The Spring context (and this singleton collector bean) is shared across test methods —
        // without clearing, a message from one test can be mistaken for another's.
        collector.received.clear();
    }

    @Test
    void eraStartedEventsDrawnResolutionStarted_producesOutcomeAppliedAfterProbabilityStateCalculated() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var winnerOutcomeId = UUID.randomUUID();
        var loserOutcomeId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, 70, loserOutcomeId, 30);
        // EventsDrawnKafkaConsumer and ResolutionStartedKafkaConsumer are independent consumer groups on
        // the same topic — nothing orders their processing relative to each other. In production the
        // gap between EventsDrawn and ResolutionStarted is naturally large (three action rounds), so this
        // synchronization only matters here, where the test publishes both back-to-back.
        awaitFutureEventIndexed(gameId, eraNumber);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(bindingsOf(collector.received))
                        .contains(PROBABILITY_STATE_CALCULATED_BINDING, OUTCOME_APPLIED_BINDING));

        var probabilityIndex = indexOfBinding(collector.received, PROBABILITY_STATE_CALCULATED_BINDING);
        var outcomeIndex = indexOfBinding(collector.received, OUTCOME_APPLIED_BINDING);
        assertThat(probabilityIndex).isLessThan(outcomeIndex);

        var outcomePayload = collector.received.get(outcomeIndex).payload();
        assertThat(outcomePayload.get("winningOutcomeId")).isEqualTo(winnerOutcomeId.toString());
        assertThat(outcomePayload.get("eventId")).isEqualTo(futureEventId.toString());
    }

    @Test
    void redeliveredResolutionStarted_doesNotDoubleResolve() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var winnerOutcomeId = UUID.randomUUID();
        var loserOutcomeId = UUID.randomUUID();
        var resolutionEventId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, 60, loserOutcomeId, 40);
        awaitFutureEventIndexed(gameId, eraNumber);
        publishResolutionStarted(gameId, eraNumber, resolutionEventId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(bindingsOf(collector.received)).contains(OUTCOME_APPLIED_BINDING));
        var countAfterFirstDelivery = collector.received.size();

        // Same envelope eventId — must be claimed-and-skipped, not re-resolved.
        publishResolutionStarted(gameId, eraNumber, resolutionEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.received).hasSize(countAfterFirstDelivery));
    }

    private void awaitFutureEventIndexed(UUID gameId, int eraNumber) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isPositive());
    }

    private void publishEraStarted(UUID gameId, int eraNumber) {
        publish(
                gameId,
                "Sessionpublish-era-started-out",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        eraNumber,
                        "cascadedEventIds",
                        List.of(),
                        "playerIds",
                        List.of()));
    }

    private void publishEventsDrawn(
            UUID gameId,
            int eraNumber,
            UUID futureEventId,
            UUID winnerOutcomeId,
            int winnerProbability,
            UUID loserOutcomeId,
            int loserProbability) {
        publish(
                gameId,
                "Sessionpublish-events-drawn-out",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        eraNumber,
                        "events",
                        List.of(Map.of(
                                "eventId",
                                futureEventId,
                                "title",
                                "Test Future Event",
                                "isCascaded",
                                false,
                                "outcomes",
                                List.of(
                                        Map.of(
                                                "outcomeId",
                                                winnerOutcomeId,
                                                "description",
                                                "winner",
                                                "initialProbability",
                                                winnerProbability),
                                        Map.of(
                                                "outcomeId",
                                                loserOutcomeId,
                                                "description",
                                                "loser",
                                                "initialProbability",
                                                loserProbability))))));
    }

    private void publishResolutionStarted(UUID gameId, int eraNumber, UUID eventId) {
        publish(
                gameId,
                "Sessionpublish-resolution-started-out",
                Map.of("gameId", gameId, "eraNumber", eraNumber),
                eventId);
    }

    private void publish(UUID gameId, String bindingName, Object payload) {
        publish(gameId, bindingName, payload, UUID.randomUUID());
    }

    private void publish(UUID gameId, String bindingName, Object payload, UUID eventId) {
        Message<Object> message = MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, GAME_EVENTS_TOPIC)
                .setHeader(KafkaHeaders.KEY, gameId.toString())
                .setHeader("eventId", eventId.toString())
                .setHeader("aggregateId", gameId.toString())
                .setHeader("aggregateType", "Game")
                .setHeader("gameId", gameId.toString())
                .setHeader("occurredAt", Instant.now())
                .setHeader("version", 1)
                .setHeader("spring.cloud.stream.sendto.destination", bindingName)
                .build();
        kafkaTemplate.send(message);
    }

    private static List<String> bindingsOf(List<TimelineEventsTestCollector.CollectedMessage> messages) {
        return messages.stream()
                .map(TimelineEventsTestCollector.CollectedMessage::bindingName)
                .toList();
    }

    private static int indexOfBinding(List<TimelineEventsTestCollector.CollectedMessage> messages, String binding) {
        return bindingsOf(messages).indexOf(binding);
    }
}
