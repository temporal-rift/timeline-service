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
 * End-to-end proof of {@code ParadoxResolutionSaga}'s force-cascade branch (sagas.md Saga 5,
 * timeline-mvp7-paradox-resolution-saga): a detected paradox opens a resolution phase whose timer
 * (2s in {@code application-test.yml}) expires with no submissions, force-cascading it into a
 * {@code ParadoxCascaded} and a single, deferred {@code EraResolutionCompleted} in reveal order.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class})
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
                60,
                secondOutcomeId,
                40,
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
        var entry1 = terminalResolutionFor(terminalResolutions, eventId1);
        assertThat(entry1)
                .containsEntry("terminalState", "OUTCOME_APPLIED")
                .containsEntry("revealIndex", 1)
                .containsEntry("winningOutcomeId", winnerOutcomeId1.toString());
        var entry2 = terminalResolutionFor(terminalResolutions, eventId2);
        assertThat(entry2)
                .containsEntry("terminalState", "OUTCOME_APPLIED")
                .containsEntry("revealIndex", 2)
                .containsEntry("winningOutcomeId", winnerOutcomeId2.toString());

        // Carried into the next era's index, exactly like a STALLED event.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM future_event_era_index WHERE event_id = ? AND era_number = ?",
                        Integer.class,
                        paradoxedEventId,
                        eraNumber + 1))
                .isEqualTo(1);

        // Resolving the next era directly proves the carried event is active again with its paradox state
        // intact (GDD §6.2): nothing broke the tie, so the same IMPOSSIBLE_ERASURE paradox is re-detected
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
     * timeline-mvp9-resolution-ordering-paradox-cards: {@code CardPlayed}/{@code SpecialActionPlayed} are now
     * buffered, not applied immediately — a round's effects only take place once its {@code ActionRoundClosed}
     * triggers the priority-ordered replay.
     */
    private void publishActionRoundClosed(UUID gameId, int eraNumber, int roundNumber) {
        publish(
                gameId,
                "ActionRoundClosed",
                Map.of("gameId", gameId, "eraNumber", eraNumber, "roundNumber", roundNumber));
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
