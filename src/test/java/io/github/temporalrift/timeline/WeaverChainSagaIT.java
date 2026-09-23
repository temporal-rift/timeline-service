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
 * Kafka-level proof of the Weaver chain saga: THREAD opens a pending link on a not-yet-resolved current-era
 * outcome; the pending link confirms (ChainLinkAdded) when its era resolves as predicted, growing the same chain
 * across era boundaries until the third link completes it; a redelivered THREAD emits only one ChainLinkThreaded;
 * GameEnded closes an incomplete chain without further chain events; and an Annihilate naming an already-resolved,
 * confirmed-linked outcome is a no-op (only the chain's open pending link, if any, can ever be invalidated).
 */
@TimelineServiceIntegrationTest
class WeaverChainSagaIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String CHAIN_LINK_THREADED = "ChainLinkThreaded";
    private static final String CHAIN_LINK_ADDED = "ChainLinkAdded";
    private static final String CHAIN_COMPLETED = "ChainCompleted";
    private static final String CHAIN_LINK_INVALIDATED = "ChainLinkInvalidated";

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
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();
        var era3Event = UUID.randomUUID();
        var era3Winner = UUID.randomUUID();
        var era4Event = UUID.randomUUID();
        var era4Winner = UUID.randomUUID();

        draftDeterministicEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, UUID.randomUUID());
        awaitChainLinkThreaded(gameId, 1);
        resolveEra(gameId, 2, era2Event, era2Winner);
        awaitChainLinkAdded(gameId, 1);

        draftDeterministicEra(gameId, 3, era3Event, era3Winner);
        publishThread(gameId, 3, weaver, era3Event, era3Winner, UUID.randomUUID());
        awaitChainLinkThreaded(gameId, 2);
        resolveEra(gameId, 3, era3Event, era3Winner);
        awaitChainLinkAdded(gameId, 2);

        draftDeterministicEra(gameId, 4, era4Event, era4Winner);
        publishThread(gameId, 4, weaver, era4Event, era4Winner, UUID.randomUUID());
        awaitChainLinkThreaded(gameId, 3);
        resolveEra(gameId, 4, era4Event, era4Winner);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(CHAIN_COMPLETED));

        var added = payloadsOf(messagesFor(gameId), CHAIN_LINK_ADDED);
        assertThat(added).hasSize(3);
        var chainId = added.get(0).get("chainId").toString();
        assertThat(added)
                .allSatisfy(payload -> assertThat(payload.get("chainId")).hasToString(chainId));
        assertThat(added.get(0)).containsEntry("chainLength", 1);
        assertThat(added.get(1)).containsEntry("chainLength", 2);
        assertThat(added.get(2)).containsEntry("chainLength", 3);
        assertThat(added.get(0).get("linkedEventId")).hasToString(era2Event.toString());
        assertThat(added.get(1).get("previousLinkEventId")).hasToString(era2Event.toString());

        var completed = payloadsOf(messagesFor(gameId), CHAIN_COMPLETED).getFirst();
        assertThat(completed.get("chainId")).hasToString(chainId);
        assertThat(completed.get("playerId")).hasToString(weaver.toString());
        assertThat((List<?>) completed.get("links")).hasSize(3);
    }

    /**
     * A confirmed chain link always references an already-resolved {@code FutureEvent}, and every
     * {@code FutureEvent} mutation — including {@code annihilateOutcome} — refuses to touch a resolved event.
     * Game-service does not restrict Annihilate's target to the current era, so a client can legitimately name a
     * past, already-resolved, confirmed-linked event. Only the chain's open pending link (naming a not-yet-resolved
     * current-era outcome) can ever be invalidated by an Annihilate; a confirmed link is untouched either way.
     */
    @Test
    void annihilateAgainstAnAlreadyResolvedConfirmedLink_doesNotInvalidateIt() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era1Event = UUID.randomUUID();
        var era1Winner = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();

        draftDeterministicEra(gameId, 1, era1Event, era1Winner);
        publishThread(gameId, 1, weaver, era1Event, era1Winner, UUID.randomUUID());
        awaitChainLinkThreaded(gameId, 1);
        resolveEra(gameId, 1, era1Event, era1Winner);
        awaitChainLinkAdded(gameId, 1);

        draftEra(gameId, 2, era2Event, era2Winner);
        publishSpecialActionPlayed(gameId, 2, UUID.randomUUID(), "ANNIHILATE", era1Event, era1Winner);
        publishActionRoundClosed(gameId, 2, 1);
        resolveEra(gameId, 2, era2Event, era2Winner);

        assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(CHAIN_LINK_INVALIDATED);
    }

    @Test
    void redeliveredThread_emitsOnlyOneChainLinkThreaded() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();
        var threadEventId = UUID.randomUUID();

        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, threadEventId);
        awaitChainLinkThreaded(gameId, 1);

        publishThread(gameId, 2, weaver, era2Event, era2Winner, threadEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), CHAIN_LINK_THREADED))
                        .hasSize(1));
    }

    @Test
    void gameEnded_closesIncompleteChainWithoutFurtherChainEvents() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();

        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, UUID.randomUUID());
        awaitChainLinkThreaded(gameId, 1);
        publishGameEnded(gameId);
        // Wait until GameEnded actually closed the saga before the ANNIHILATE arrives; a still-open saga would
        // answer it with ChainLinkInvalidated within the assertion window below.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM weaver_chain_saga WHERE game_id = ? AND status = 'OPEN'",
                                Integer.class,
                                gameId))
                        .isZero());
        publishSpecialActionPlayed(gameId, 2, UUID.randomUUID(), "ANNIHILATE", era2Event, era2Winner);
        publishActionRoundClosed(gameId, 2, 1);

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(CHAIN_LINK_INVALIDATED));
    }

    private void draftEra(UUID gameId, int eraNumber, UUID eventId, UUID winner) {
        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, eventId, winner);
        awaitFutureEventIndexed(gameId, eraNumber);
    }

    /**
     * Drafts an era whose threaded outcome is the only eligible winner: the two losing outcomes are
     * annihilated (round 1 closes before the THREAD is published, and the single consumer group replays
     * the round strictly before applying the THREAD, so no awaiting of the replay itself is needed).
     * Without this, the THREAD reward's ceiling-clamped push redistributes weight onto the losers
     * (100 becomes 90/5/5) and the weighted draw only <i>likely</i> confirms the link — a 10% flake
     * per era that bit {@code threadAcrossThreeEras_completesChainAndEndsSaga} on {@code main}.
     */
    private void draftDeterministicEra(UUID gameId, int eraNumber, UUID eventId, UUID winner) {
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();
        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, eventId, winner, second, third);
        awaitFutureEventIndexed(gameId, eraNumber);
        publishSpecialActionPlayed(gameId, eraNumber, UUID.randomUUID(), "ANNIHILATE", eventId, second);
        publishSpecialActionPlayed(gameId, eraNumber, UUID.randomUUID(), "ANNIHILATE", eventId, third);
        publishActionRoundClosed(gameId, eraNumber, 1);
    }

    private void resolveEra(UUID gameId, int eraNumber, UUID eventId, UUID expectedWinnerId) {
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), OUTCOME_APPLIED))
                        .anySatisfy(payload -> {
                            assertThat(payload.get("eventId")).hasToString(eventId.toString());
                            assertThat(payload.get("winningOutcomeId")).hasToString(expectedWinnerId.toString());
                        }));
    }

    private void awaitChainLinkThreaded(UUID gameId, int expectedCount) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), CHAIN_LINK_THREADED))
                        .hasSize(expectedCount));
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
        payload.put("sourceEventId", null);
        payload.put("sourceOutcomeId", null);
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        payload.put("targetPlayerId", null);
        publish(gameId, "SpecialActionPlayed", payload, eventId);
    }

    private void publishSpecialActionPlayed(
            UUID gameId, int eraNumber, UUID playerId, String specialAction, UUID targetEventId, UUID targetOutcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", playerId);
        payload.put("faction", "ERASERS");
        payload.put("specialAction", specialAction);
        payload.put("sourceEventId", null);
        payload.put("sourceOutcomeId", null);
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        payload.put("targetPlayerId", null);
        publish(gameId, "SpecialActionPlayed", payload);
    }

    private void publishActionRoundClosed(UUID gameId, int eraNumber, int roundNumber) {
        publish(
                gameId,
                "ActionRoundClosed",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        eraNumber,
                        "roundNumber",
                        roundNumber,
                        "closedReason",
                        "ALL_SUBMITTED",
                        "totalActions",
                        1));
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

    /**
     * {@code winnerOutcomeId} is weighted 100 against two 0-weight outcomes — this file tests Weaver chain
     * mechanics, not the weighted draw itself, so the resolved winner must stay deterministic (a nonzero-weight
     * outcome is only guaranteed, not merely likely, to win the weighted-outcome-resolution capability's draw
     * when the other eligible outcomes carry zero weight).
     */
    private void publishEventsDrawn(UUID gameId, int eraNumber, UUID futureEventId, UUID winnerOutcomeId) {
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, UUID.randomUUID(), UUID.randomUUID());
    }

    private void publishEventsDrawn(
            UUID gameId,
            int eraNumber,
            UUID futureEventId,
            UUID winnerOutcomeId,
            UUID secondOutcomeId,
            UUID thirdOutcomeId) {
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
                                        outcome(winnerOutcomeId, "winner", 100),
                                        outcome(secondOutcomeId, "second", 0),
                                        outcome(thirdOutcomeId, "third", 0))))));
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
