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
 * Kafka-level proof of the Weaver chain saga: THREAD anchors a current-era, not-yet-resolved outcome to a past
 * resolved one, growing the same chain across era boundaries until the third link completes it; a redelivered
 * THREAD emits once; GameEnded closes an incomplete chain without further chain events; and an Annihilate naming
 * an already-resolved (and possibly chain-linked) outcome no longer crashes the round replay (see
 * temporal-rift/timeline-service#108 for actually invalidating the link).
 */
@TimelineServiceIntegrationTest
class WeaverChainSagaIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
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
    void threadAcrossFourEras_completesChainAndEndsSaga() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era1Event = UUID.randomUUID();
        var era1Winner = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();
        var era3Event = UUID.randomUUID();
        var era3Winner = UUID.randomUUID();
        var era4Event = UUID.randomUUID();
        var era4Winner = UUID.randomUUID();

        draftEra(gameId, 1, era1Event, era1Winner);
        resolveEra(gameId, 1, era1Event);

        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, era1Event, era1Winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);
        resolveEra(gameId, 2, era2Event);

        draftEra(gameId, 3, era3Event, era3Winner);
        publishThread(gameId, 3, weaver, era3Event, era3Winner, era2Event, era2Winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 2);
        resolveEra(gameId, 3, era3Event);

        draftEra(gameId, 4, era4Event, era4Winner);
        publishThread(gameId, 4, weaver, era4Event, era4Winner, era3Event, era3Winner, UUID.randomUUID());

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
        assertThat(added.get(0).get("sourceEventId")).hasToString(era2Event.toString());
        assertThat(added.get(0).get("sourceOutcomeId")).hasToString(era2Winner.toString());
        assertThat(added.get(1).get("previousLinkEventId")).hasToString(era1Event.toString());

        var completed = payloadsOf(messagesFor(gameId), CHAIN_COMPLETED).getFirst();
        assertThat(completed.get("chainId")).hasToString(chainId);
        assertThat(completed.get("playerId")).hasToString(weaver.toString());
        assertThat((List<?>) completed.get("links")).hasSize(3);
    }

    /**
     * A chain link's target is always an already-resolved {@code FutureEvent} (only a resolved outcome can be
     * Threaded to), and every {@code FutureEvent} mutation — including {@code annihilateOutcome} — refuses to
     * touch a resolved event. Game-service does not restrict Annihilate's target to the current era, so a client
     * can legitimately name a past, already-resolved (possibly chain-linked) event. Before this fix, that
     * combination let an uncaught exception crash the whole round-replay transaction instead of just leaving the
     * chain untouched; tracked to actually invalidate the link as temporal-rift/timeline-service#108. This proves
     * only that the crash is fixed: era2's own unrelated resolution still completes normally afterward, and no
     * chain event is produced.
     */
    @Test
    void annihilateAgainstAnAlreadyResolvedLinkedOutcome_doesNotCrashTheRoundReplay() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era1Event = UUID.randomUUID();
        var era1Winner = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();

        draftEra(gameId, 1, era1Event, era1Winner);
        resolveEra(gameId, 1, era1Event);
        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, era1Event, era1Winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);

        publishSpecialActionPlayed(gameId, 2, UUID.randomUUID(), "ANNIHILATE", era1Event, era1Winner);
        publishActionRoundClosed(gameId, 2, 1);
        resolveEra(gameId, 2, era2Event);

        assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(CHAIN_LINK_INVALIDATED);
    }

    @Test
    void redeliveredThread_emitsOnlyOneChainLinkAdded() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era1Event = UUID.randomUUID();
        var era1Winner = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();
        var threadEventId = UUID.randomUUID();

        draftEra(gameId, 1, era1Event, era1Winner);
        resolveEra(gameId, 1, era1Event);
        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, era1Event, era1Winner, threadEventId);
        awaitChainLinkAdded(gameId, 1);

        publishThread(gameId, 2, weaver, era2Event, era2Winner, era1Event, era1Winner, threadEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), CHAIN_LINK_ADDED))
                        .hasSize(1));
    }

    @Test
    void gameEnded_closesIncompleteChainWithoutFurtherChainEvents() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var era1Event = UUID.randomUUID();
        var era1Winner = UUID.randomUUID();
        var era2Event = UUID.randomUUID();
        var era2Winner = UUID.randomUUID();

        draftEra(gameId, 1, era1Event, era1Winner);
        resolveEra(gameId, 1, era1Event);
        draftEra(gameId, 2, era2Event, era2Winner);
        publishThread(gameId, 2, weaver, era2Event, era2Winner, era1Event, era1Winner, UUID.randomUUID());
        awaitChainLinkAdded(gameId, 1);
        publishGameEnded(gameId);
        // Wait until GameEnded actually closed the saga before the ANNIHILATE arrives; a still-open saga would
        // answer it with ChainLinkInvalidated within the assertion window below.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM weaver_chain_saga WHERE game_id = ? AND status = 'OPEN'",
                                Integer.class,
                                gameId))
                        .isZero());
        publishSpecialActionPlayed(gameId, 2, UUID.randomUUID(), "ANNIHILATE", era1Event, era1Winner);
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

    private void resolveEra(UUID gameId, int eraNumber, UUID eventId) {
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(messagesFor(gameId), OUTCOME_APPLIED))
                        .anySatisfy(
                                payload -> assertThat(payload.get("eventId")).hasToString(eventId.toString())));
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
            UUID gameId,
            int eraNumber,
            UUID playerId,
            UUID sourceEventId,
            UUID sourceOutcomeId,
            UUID targetEventId,
            UUID targetOutcomeId,
            UUID eventId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", playerId);
        payload.put("faction", "WEAVERS");
        payload.put("specialAction", "THREAD");
        payload.put("sourceEventId", sourceEventId);
        payload.put("sourceOutcomeId", sourceOutcomeId);
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
