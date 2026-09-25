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
 * End-to-end proof of {@code ParadoxResolutionSaga}'s force-cascade branch: a detected paradox opens a resolution
 * phase whose timer
 * (2s in {@code application-test.yml}) expires with no submissions, force-cascading it into a
 * {@code ParadoxCascaded} and a single, deferred {@code EraResolutionCompleted} in reveal order.
 */
@TimelineServiceIntegrationTest
class ParadoxResolutionForceCascadeIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_RESOLUTION_PHASE_STARTED = "ParadoxResolutionPhaseStarted";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";

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
    void oneParadoxAndTwoCleanEvents_deferBarrierThenForceCascadeProducesOrderedBarrier() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var winnerOutcomeId1 = UUID.randomUUID();
        var loserOutcomeId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();
        var winnerOutcomeId2 = UUID.randomUUID();
        var loserOutcomeId2 = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawnThreeEvents(
                gameId,
                eraNumber,
                paradoxedEventId,
                annihilatedOutcomeId,
                100,
                secondOutcomeId,
                0,
                eventId1,
                winnerOutcomeId1,
                70,
                loserOutcomeId1,
                30,
                eventId2,
                winnerOutcomeId2,
                65,
                loserOutcomeId2,
                35);
        awaitFutureEventsIndexed(gameId, eraNumber, 3);

        publishSpecialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publishActionRoundClosed(gameId, eraNumber, 1);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        // The barrier must not appear yet — it waits for the paradox's resolution phase to conclude.
        assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(ERA_RESOLUTION_COMPLETED);

        // Test timer is 2s (application-test.yml); sweep interval is 1s (application.yml) — either can fire it.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_CASCADED, ERA_RESOLUTION_COMPLETED));

        var messages = messagesFor(gameId);
        var paradoxCascadedPayload = messages.stream()
                .filter(m -> PARADOX_CASCADED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        assertThat(paradoxCascadedPayload).containsEntry("affectedEventId", paradoxedEventId.toString());

        var eraResolutionCompletedPayload = messages.stream()
                .filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        var terminalResolutions = (List<?>) eraResolutionCompletedPayload.get("terminalResolutions");
        assertThat(terminalResolutions).hasSize(3);
        // Membership/field checks below don't prove list order — assert the actual sequence too, since a
        // payload ordered [entry1, entry2, paradoxedEntry] would otherwise still pass.
        assertThat(terminalResolutions.stream()
                        .map(entry -> asMap(entry).get("eventId"))
                        .toList())
                .containsExactly(paradoxedEventId.toString(), eventId1.toString(), eventId2.toString());

        var paradoxedEntry = terminalResolutionFor(terminalResolutions, paradoxedEventId);
        assertThat(paradoxedEntry)
                .containsEntry("terminalState", "CASCADED")
                .containsEntry("revealIndex", 0)
                .doesNotContainKey("winningOutcomeId");
        // The winner is now drawn, not deterministically whichever
        // outcome leads — this test's focus is the barrier ordering around the paradoxed event, so just confirm
        // each clean event resolved to one of its own valid outcomes.
        var entry1 = terminalResolutionFor(terminalResolutions, eventId1);
        assertThat(entry1).containsEntry("terminalState", "OUTCOME_APPLIED").containsEntry("revealIndex", 1);
        assertThat((String) entry1.get("winningOutcomeId"))
                .isIn(winnerOutcomeId1.toString(), loserOutcomeId1.toString());
        var entry2 = terminalResolutionFor(terminalResolutions, eventId2);
        assertThat(entry2).containsEntry("terminalState", "OUTCOME_APPLIED").containsEntry("revealIndex", 2);
        assertThat((String) entry2.get("winningOutcomeId"))
                .isIn(winnerOutcomeId2.toString(), loserOutcomeId2.toString());

        // Carried into the next era's index, exactly like a STALLED event.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM future_event_era_index WHERE event_id = ? AND era_number = ?",
                        Integer.class,
                        paradoxedEventId,
                        eraNumber + 1))
                .isEqualTo(1);

        // Resolving the next era directly proves the carried event is active again with its paradox state
        // intact: nothing restored eligible weight, so the same IMPOSSIBLE_ERASURE paradox is re-detected
        // from the exact carried outcome state — not silently dropped or force-resolved.
        var nextEraResolutionEventId = UUID.randomUUID();
        publishResolutionStarted(gameId, eraNumber + 1, nextEraResolutionEventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var nextEraParadoxDetected = messagesFor(gameId).stream()
                    .filter(m -> PARADOX_DETECTED.equals(m.eventType()))
                    .map(TimelineEventsTestCollector.CollectedMessage::payload)
                    .filter(payload -> Integer.valueOf(eraNumber + 1).equals(payload.get("eraNumber")))
                    .findFirst();
            assertThat(nextEraParadoxDetected).isPresent();
            var paradoxes = (List<?>) nextEraParadoxDetected.get().get("paradoxes");
            assertThat(paradoxes)
                    .singleElement()
                    .satisfies(p -> assertThat(asMap(p)).containsEntry("affectedEventId", paradoxedEventId.toString()));
        });
    }

    @Test
    void carriedEventLosesAllEligibleOutcomes_stabilizeCannotForceResolution_cascadesAcrossRepeatedEras() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();

        publishEraStarted(gameId, 1);
        publishEventsDrawnSingleThreeOutcomeEvent(gameId, 1, eventId, outcomeA, 100, outcomeB, 0, outcomeC, 0);
        awaitFutureEventsIndexed(gameId, 1, 1);

        // Annihilating the outcome holding all the weight leaves nothing to draw: IMPOSSIBLE_ERASURE.
        publishSpecialActionPlayed(gameId, 1, eventId, "ANNIHILATE", outcomeA);
        publishActionRoundClosed(gameId, 1, 1);
        publishResolutionStarted(gameId, 1, UUID.randomUUID());
        awaitEventCascaded(gameId, 1, eventId);
        awaitCarriedForwardToEra(gameId, eventId, 2);

        publishSpecialActionPlayed(gameId, 2, eventId, "ANNIHILATE", outcomeB);
        publishActionRoundClosed(gameId, 2, 1);
        publishResolutionStarted(gameId, 2, UUID.randomUUID());
        awaitEventCascaded(gameId, 2, eventId);
        awaitCarriedForwardToEra(gameId, eventId, 3);

        // Annihilating C leaves no eligible outcome; STABILIZE against it must not force a draw.
        publishSpecialActionPlayed(gameId, 3, eventId, "ANNIHILATE", outcomeC);
        publishActionRoundClosed(gameId, 3, 1);
        publishResolutionStarted(gameId, 3, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesForEra(gameId, 3))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        publishParadoxResolutionCardPlayed(gameId, 3, eventId, "STABILIZE");
        awaitEventCascaded(gameId, 3, eventId);
        awaitCarriedForwardToEra(gameId, eventId, 4);

        publishResolutionStarted(gameId, 4, UUID.randomUUID());
        awaitEventCascaded(gameId, 4, eventId);
        awaitCarriedForwardToEra(gameId, eventId, 5);

        publishResolutionStarted(gameId, 5, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesForEra(gameId, 5))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        publishParadoxResolutionCardPlayed(gameId, 5, eventId, "STABILIZE");
        awaitEventCascaded(gameId, 5, eventId);
        awaitCarriedForwardToEra(gameId, eventId, 6);

        // Across every era, this event never resolved to an outcome and never published ParadoxResolved.
        assertThat(messagesFor(gameId))
                .filteredOn(m -> "OutcomeApplied".equals(m.eventType())
                        && eventId.toString().equals(m.payload().get("eventId")))
                .isEmpty();
        assertThat(messagesFor(gameId))
                .filteredOn(m -> "ParadoxResolved".equals(m.eventType()))
                .isEmpty();
    }

    private void awaitEventCascaded(UUID gameId, int eraNumber, UUID eventId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var cascadedThisEra = messagesFor(gameId).stream()
                    .filter(m -> PARADOX_CASCADED.equals(m.eventType()))
                    .map(TimelineEventsTestCollector.CollectedMessage::payload)
                    .filter(p -> Integer.valueOf(eraNumber).equals(p.get("eraNumber")))
                    .filter(p -> eventId.toString().equals(p.get("affectedEventId")))
                    .toList();
            assertThat(cascadedThisEra).isNotEmpty();

            var barrier = messagesFor(gameId).stream()
                    .filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType()))
                    .map(TimelineEventsTestCollector.CollectedMessage::payload)
                    .filter(p -> Integer.valueOf(eraNumber).equals(p.get("eraNumber")))
                    .findFirst();
            assertThat(barrier).isPresent();
            var terminalResolutions = (List<?>) barrier.get().get("terminalResolutions");
            assertThat(terminalResolutionFor(terminalResolutions, eventId))
                    .containsEntry("terminalState", "CASCADED")
                    .doesNotContainKey("winningOutcomeId");
        });
    }

    private void awaitCarriedForwardToEra(UUID gameId, UUID eventId, int eraNumber) {
        var sql = "SELECT COUNT(*) FROM future_event_era_index "
                + "WHERE game_id = ? AND event_id = ? AND era_number = ?";
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(jdbcTemplate.queryForObject(sql, Integer.class, gameId, eventId, eraNumber))
                                .isEqualTo(1));
    }

    private List<String> eventTypesForEra(UUID gameId, int eraNumber) {
        return messagesFor(gameId).stream()
                .filter(m -> Integer.valueOf(eraNumber).equals(m.payload().get("eraNumber")))
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }

    private void publishEventsDrawnSingleThreeOutcomeEvent(
            UUID gameId,
            int eraNumber,
            UUID eventId,
            UUID outcomeIdA,
            int probabilityA,
            UUID outcomeIdB,
            int probabilityB,
            UUID outcomeIdC,
            int probabilityC) {
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
                                eventId,
                                "title",
                                "Test Future Event",
                                "carryOverState",
                                "FRESH",
                                "outcomes",
                                List.of(
                                        Map.of(
                                                "outcomeId",
                                                outcomeIdA,
                                                "description",
                                                "a",
                                                "initialProbability",
                                                probabilityA),
                                        Map.of(
                                                "outcomeId",
                                                outcomeIdB,
                                                "description",
                                                "b",
                                                "initialProbability",
                                                probabilityB),
                                        Map.of(
                                                "outcomeId",
                                                outcomeIdC,
                                                "description",
                                                "c",
                                                "initialProbability",
                                                probabilityC))))));
    }

    private void publishParadoxResolutionCardPlayed(UUID gameId, int eraNumber, UUID targetEventId, String cardType) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("playerId", UUID.randomUUID());
        payload.put("cardInstanceId", UUID.randomUUID());
        payload.put("cardType", cardType);
        payload.put("grade", "I");
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", UUID.randomUUID());
        publish(gameId, "ParadoxResolutionCardPlayed", payload);
    }

    private void awaitFutureEventsIndexed(UUID gameId, int eraNumber, int expectedCount) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isEqualTo(expectedCount));
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

    private void publishEventsDrawnThreeEvents(
            UUID gameId,
            int eraNumber,
            UUID eventId0,
            UUID outcomeId0a,
            int probability0a,
            UUID outcomeId0b,
            int probability0b,
            UUID eventId1,
            UUID outcomeId1a,
            int probability1a,
            UUID outcomeId1b,
            int probability1b,
            UUID eventId2,
            UUID outcomeId2a,
            int probability2a,
            UUID outcomeId2b,
            int probability2b) {
        publish(
                gameId,
                "EventsDrawn",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        eraNumber,
                        "events",
                        List.of(
                                eventMap(eventId0, outcomeId0a, probability0a, outcomeId0b, probability0b),
                                eventMap(eventId1, outcomeId1a, probability1a, outcomeId1b, probability1b),
                                eventMap(eventId2, outcomeId2a, probability2a, outcomeId2b, probability2b))));
    }

    private Map<String, Object> eventMap(
            UUID eventId, UUID outcomeId1, int probability1, UUID outcomeId2, int probability2) {
        return Map.of(
                "eventId",
                eventId,
                "title",
                "Test Future Event",
                "carryOverState",
                "FRESH",
                "outcomes",
                List.of(
                        Map.of("outcomeId", outcomeId1, "description", "a", "initialProbability", probability1),
                        Map.of("outcomeId", outcomeId2, "description", "b", "initialProbability", probability2)));
    }

    private void publishSpecialActionPlayed(
            UUID gameId, int eraNumber, UUID targetEventId, String specialAction, UUID targetOutcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", UUID.randomUUID());
        payload.put("faction", "ERASERS");
        payload.put("specialAction", specialAction);
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        payload.put("targetPlayerId", null);
        publish(gameId, "SpecialActionPlayed", payload);
    }

    private void publishResolutionStarted(UUID gameId, int eraNumber, UUID eventId) {
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber), eventId);
    }

    /**
     * the round-action ordering design: {@code CardPlayed}/{@code SpecialActionPlayed} are now
     * buffered, not applied immediately — a round's effects only take place once its {@code ActionRoundClosed}
     * triggers the priority-ordered replay.
     */
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> terminalResolutionFor(List<?> terminalResolutions, UUID eventId) {
        return terminalResolutions.stream()
                .map(ParadoxResolutionForceCascadeIT::asMap)
                .filter(entry -> eventId.toString().equals(entry.get("eventId")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
