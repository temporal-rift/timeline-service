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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Kafka-level proof of the Weaver chain saga: one THREAD per era grows the same chain across era
 * boundaries until the third link completes it, UNRAVEL breaks an open chain, a redelivered THREAD
 * emits once, and GameEnded closes an incomplete chain without further chain events.
 */
@TimelineServiceIntegrationTest
class WeaverChainSagaIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String CHAIN_LINK_ADDED = "ChainLinkAdded";
    private static final String CHAIN_COMPLETED = "ChainCompleted";
    private static final String CHAIN_BROKEN = "ChainBroken";

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCollector() {
        collector.received.clear();
    }

    @Test
    void threadAcrossThreeEras_completesChainAndEndsSaga() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var firstEvent = UUID.randomUUID();
        var firstWinner = UUID.randomUUID();
        var secondEvent = UUID.randomUUID();
        var secondWinner = UUID.randomUUID();
        var thirdEvent = UUID.randomUUID();
        var thirdWinner = UUID.randomUUID();

        resolveEraWithWinner(gameId, 1, firstEvent, firstWinner);
        publishThread(gameId, 1, weaver, firstEvent, firstWinner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);

        resolveEraWithWinner(gameId, 2, secondEvent, secondWinner);
        publishThread(gameId, 2, weaver, secondEvent, secondWinner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 2);

        resolveEraWithWinner(gameId, 3, thirdEvent, thirdWinner);
        publishThread(gameId, 3, weaver, thirdEvent, thirdWinner, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(CHAIN_COMPLETED));

        var added = payloadsOf(messagesFor(gameId), CHAIN_LINK_ADDED);
        assertThat(added).hasSize(3);
        var chainId = added.get(0).get("chainId").toString();
        assertThat(added)
                .allSatisfy(
                        payload -> assertThat(payload.get("chainId").toString()).isEqualTo(chainId));
        assertThat(added.get(0)).containsEntry("chainLength", 1);
        assertThat(added.get(1)).containsEntry("chainLength", 2);
        assertThat(added.get(2)).containsEntry("chainLength", 3);
        assertThat(added.get(1).get("previousLinkEventId").toString()).isEqualTo(firstEvent.toString());

        var completed = payloadsOf(messagesFor(gameId), CHAIN_COMPLETED).getFirst();
        assertThat(completed.get("chainId").toString()).isEqualTo(chainId);
        assertThat(completed.get("playerId").toString()).isEqualTo(weaver.toString());
        assertThat((List<?>) completed.get("links")).hasSize(3);
    }

    @Test
    void unravel_breaksOpenChainAndEndsSagaInFailure() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var unraveler = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var winner = UUID.randomUUID();

        resolveEraWithWinner(gameId, 1, eventId, winner);
        publishThread(gameId, 1, weaver, eventId, winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);
        publishUnravel(gameId, 1, unraveler, weaver);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(CHAIN_BROKEN));

        var broken = payloadsOf(messagesFor(gameId), CHAIN_BROKEN).getFirst();
        assertThat(broken.get("targetPlayerId").toString()).isEqualTo(weaver.toString());
        assertThat(broken.get("brokenByPlayerId").toString()).isEqualTo(unraveler.toString());
        assertThat(broken).containsEntry("chainLengthAtBreak", 1);
    }

    @Test
    void redeliveredThread_emitsOnlyOneChainLinkAdded() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var winner = UUID.randomUUID();
        var threadEventId = UUID.randomUUID();

        resolveEraWithWinner(gameId, 1, eventId, winner);
        publishThread(gameId, 1, weaver, eventId, winner, threadEventId);
        awaitChainLinkAdded(gameId, 1);

        publishThread(gameId, 1, weaver, eventId, winner, threadEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), CHAIN_LINK_ADDED))
                        .hasSize(1));
    }

    @Test
    void gameEnded_closesIncompleteChainWithoutFurtherChainEvents() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var unraveler = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var winner = UUID.randomUUID();

        resolveEraWithWinner(gameId, 1, eventId, winner);
        publishThread(gameId, 1, weaver, eventId, winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);
        publishGameEnded(gameId);
        // Wait until GameEnded actually closed the saga before the UNRAVEL arrives; a still-open saga
        // would answer the UNRAVEL with ChainBroken within the assertion window below.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM weaver_chain_saga WHERE game_id = ? AND status = 'OPEN'",
                                Integer.class,
                                gameId))
                        .isZero());
        publishUnravel(gameId, 1, unraveler, weaver);

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(CHAIN_BROKEN));
    }

    private void resolveEraWithWinner(UUID gameId, int eraNumber, UUID eventId, UUID winner) {
        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, eventId, winner);
        awaitFutureEventIndexed(gameId, eraNumber);
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(OUTCOME_APPLIED));
    }

    private void awaitChainLinkAdded(UUID gameId, int chainLength) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), CHAIN_LINK_ADDED))
                        .anySatisfy(payload -> assertThat(payload).containsEntry("chainLength", chainLength)));
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

    private void publishThread(
            UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId, UUID eventId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", playerId);
        payload.put("faction", "WEAVERS");
        payload.put("specialAction", "THREAD");
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        payload.put("targetPlayerId", null);
        publish(gameId, "SpecialActionPlayed", payload, eventId);
    }

    private void publishUnravel(UUID gameId, int eraNumber, UUID actingPlayerId, UUID targetPlayerId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", actingPlayerId);
        payload.put("faction", "WEAVERS");
        payload.put("specialAction", "UNRAVEL");
        payload.put("targetEventId", null);
        payload.put("targetOutcomeId", null);
        payload.put("targetPlayerId", targetPlayerId);
        publish(gameId, "SpecialActionPlayed", payload);
    }

    private void publishGameEnded(UUID gameId) {
        publish(
                gameId,
                "GameEnded",
                Map.of("gameId", gameId, "endReason", "SCORE_THRESHOLD", "finalScores", List.of()));
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
                        "carryOverEventIds",
                        List.of(),
                        "playerIds",
                        List.of()));
    }

    private void publishEventsDrawn(UUID gameId, int eraNumber, UUID futureEventId, UUID winnerOutcomeId) {
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
                                "carryOverState",
                                "FRESH",
                                "outcomes",
                                List.of(
                                        outcome(winnerOutcomeId, "winner", 60),
                                        outcome(UUID.randomUUID(), "second", 25),
                                        outcome(UUID.randomUUID(), "third", 15))))));
    }

    private static Map<String, Object> outcome(UUID outcomeId, String description, int initialProbability) {
        return Map.of("outcomeId", outcomeId, "description", description, "initialProbability", initialProbability);
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
                .setHeader("occurredAt", Instant.now().toString())
                .setHeader("version", "1")
                .setHeader("eventType", eventType)
                .build();
        kafkaTemplate.send(message);
    }

    private List<TimelineEventsTestCollector.CollectedMessage> messagesFor(UUID gameId) {
        return collector.received.stream()
                .filter(m -> gameId.toString().equals(m.payload().get("gameId")))
                .toList();
    }

    private static List<String> eventTypesOf(List<TimelineEventsTestCollector.CollectedMessage> messages) {
        return messages.stream()
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }

    private static List<Map<String, Object>> payloadsOf(
            List<TimelineEventsTestCollector.CollectedMessage> messages, String eventType) {
        return messages.stream()
                .filter(m -> eventType.equals(m.eventType()))
                .map(TimelineEventsTestCollector.CollectedMessage::payload)
                .toList();
    }
}
