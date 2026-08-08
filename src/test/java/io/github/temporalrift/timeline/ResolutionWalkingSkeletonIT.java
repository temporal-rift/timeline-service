package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 * — lands on {@code timeline.events}. Also confirms the {@code eventType}
 * header round-trips end-to-end (design.md Decision 2).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class})
class ResolutionWalkingSkeletonIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String PROBABILITY_STATE_CALCULATED = "ProbabilityStateCalculated";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";

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
                .untilAsserted(() -> assertThat(eventTypesOf(collector.received))
                        .contains(PROBABILITY_STATE_CALCULATED, OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));

        var probabilityIndex = indexOfEventType(collector.received, PROBABILITY_STATE_CALCULATED);
        var outcomeIndex = indexOfEventType(collector.received, OUTCOME_APPLIED);
        var eraResolutionCompletedIndex = indexOfEventType(collector.received, ERA_RESOLUTION_COMPLETED);
        assertThat(probabilityIndex).isLessThan(outcomeIndex);
        assertThat(outcomeIndex).isLessThan(eraResolutionCompletedIndex);

        var outcomePayload = collector.received.get(outcomeIndex).payload();
        assertThat(outcomePayload.get("winningOutcomeId")).isEqualTo(winnerOutcomeId.toString());
        assertThat(outcomePayload.get("eventId")).isEqualTo(futureEventId.toString());

        var eraResolutionCompletedPayload =
                collector.received.get(eraResolutionCompletedIndex).payload();
        assertThat((List<?>) eraResolutionCompletedPayload.get("terminalResolutions"))
                .singleElement()
                .satisfies(entry -> {
                    @SuppressWarnings("unchecked")
                    var terminalResolution = (Map<?, ?>) entry;
                    assertThat(terminalResolution.get("eventId")).isEqualTo(futureEventId.toString());
                    assertThat(terminalResolution.get("revealIndex")).isEqualTo(0);
                    assertThat(terminalResolution.get("terminalState")).isEqualTo("OUTCOME_APPLIED");
                    assertThat(terminalResolution.get("winningOutcomeId")).isEqualTo(winnerOutcomeId.toString());
                });
    }

    @Test
    void redeliveredResolutionStarted_doesNotDoubleResolveOrDuplicateEraResolutionCompleted() {
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
                .untilAsserted(() -> assertThat(eventTypesOf(collector.received))
                        .contains(OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));
        var countAfterFirstDelivery = collector.received.size();

        // Same envelope eventId — must be claimed-and-skipped, not re-resolved.
        publishResolutionStarted(gameId, eraNumber, resolutionEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.received).hasSize(countAfterFirstDelivery));
        assertThat(eventTypesOf(collector.received))
                .filteredOn(ERA_RESOLUTION_COMPLETED::equals)
                .hasSize(1);
    }

    @Test
    void pushCardPlayedBeforeResolution_flipsTheWinningOutcome() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var initialWinnerOutcomeId = UUID.randomUUID();
        var pushedOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawnThreeOutcomes(
                gameId, eraNumber, futureEventId, initialWinnerOutcomeId, 50, pushedOutcomeId, 35, thirdOutcomeId, 15);
        awaitFutureEventIndexed(gameId, eraNumber);

        // Configured push-shift is +20 (application.yml game.rules.probability.push-shift): 35 + 20 = 55,
        // enough to overtake the initial 50-probability winner. Published immediately followed by
        // ResolutionStarted with no synchronization in between — CardPlayedAndResolutionKafkaConsumer
        // handles both event types on the same consumer group/partition, so Kafka's in-partition
        // ordering alone (not a test-only wait) guarantees the shift is applied before resolution runs
        // (design.md Decision 1/3, revised after PR #25 review).
        publishCardPlayed(gameId, eraNumber, futureEventId, "PUSH", null, pushedOutcomeId);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(collector.received)).contains(OUTCOME_APPLIED));

        var outcomeIndex = indexOfEventType(collector.received, OUTCOME_APPLIED);
        var outcomePayload = collector.received.get(outcomeIndex).payload();
        assertThat(outcomePayload.get("winningOutcomeId")).isEqualTo(pushedOutcomeId.toString());
    }

    @Test
    void amplifyThenSuppress_doublesTheConfiguredSuppressMagnitudeAndFlipsTheWinner() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var initialWinnerOutcomeId = UUID.randomUUID();
        var risingOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        // configured suppress-shift is -20 (application.yml game.rules.probability). A single SUPPRESS on
        // the 70-probability outcome only drops it to 50 (still the winner); AMPLIFY doubling it to -40
        // drops it to 30, below the 47 the second outcome is redistributed up to.
        publishEventsDrawnThreeOutcomes(
                gameId, eraNumber, futureEventId, initialWinnerOutcomeId, 70, risingOutcomeId, 20, thirdOutcomeId, 10);
        awaitFutureEventIndexed(gameId, eraNumber);

        publishCardPlayed(gameId, eraNumber, futureEventId, "AMPLIFY", null, null);
        publishCardPlayed(gameId, eraNumber, futureEventId, "SUPPRESS", null, initialWinnerOutcomeId);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(collector.received)).contains(OUTCOME_APPLIED));

        var outcomeIndex = indexOfEventType(collector.received, OUTCOME_APPLIED);
        var outcomePayload = collector.received.get(outcomeIndex).payload();
        assertThat(outcomePayload.get("winningOutcomeId")).isEqualTo(risingOutcomeId.toString());
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
                "EraStarted",
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
                "EventsDrawn",
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

    private void publishEventsDrawnThreeOutcomes(
            UUID gameId,
            int eraNumber,
            UUID futureEventId,
            UUID outcomeId1,
            int probability1,
            UUID outcomeId2,
            int probability2,
            UUID outcomeId3,
            int probability3) {
        publish(
                gameId,
                "EventsDrawn",
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
                                                outcomeId1,
                                                "description",
                                                "first",
                                                "initialProbability",
                                                probability1),
                                        Map.of(
                                                "outcomeId",
                                                outcomeId2,
                                                "description",
                                                "second",
                                                "initialProbability",
                                                probability2),
                                        Map.of(
                                                "outcomeId",
                                                outcomeId3,
                                                "description",
                                                "third",
                                                "initialProbability",
                                                probability3))))));
    }

    private void publishCardPlayed(
            UUID gameId,
            int eraNumber,
            UUID targetEventId,
            String cardType,
            UUID sourceOutcomeId,
            UUID targetOutcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", UUID.randomUUID());
        payload.put("cardInstanceId", UUID.randomUUID());
        payload.put("cardType", cardType);
        payload.put("targetEventId", targetEventId);
        payload.put("sourceOutcomeId", sourceOutcomeId);
        payload.put("targetOutcomeId", targetOutcomeId);
        publish(gameId, "CardPlayed", payload);
    }

    private void publishResolutionStarted(UUID gameId, int eraNumber, UUID eventId) {
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber), eventId);
    }

    private void publish(UUID gameId, String eventType, Object payload) {
        publish(gameId, eventType, payload, UUID.randomUUID());
    }

    private void publish(UUID gameId, String eventType, Object payload, UUID eventId) {
        Message<Object> message = MessageBuilder.withPayload(payload)
                .setHeader(KafkaHeaders.TOPIC, GAME_EVENTS_TOPIC)
                .setHeader(KafkaHeaders.KEY, gameId.toString())
                .setHeader("eventId", eventId.toString())
                .setHeader("aggregateId", gameId.toString())
                .setHeader("aggregateType", "Game")
                .setHeader("gameId", gameId.toString())
                .setHeader("occurredAt", Instant.now())
                .setHeader("version", 1)
                .setHeader("eventType", eventType)
                .build();
        kafkaTemplate.send(message);
    }

    private static List<String> eventTypesOf(List<TimelineEventsTestCollector.CollectedMessage> messages) {
        return messages.stream()
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }

    private static int indexOfEventType(List<TimelineEventsTestCollector.CollectedMessage> messages, String eventType) {
        return eventTypesOf(messages).indexOf(eventType);
    }
}
