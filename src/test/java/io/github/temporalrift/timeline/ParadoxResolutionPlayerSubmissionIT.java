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
 * End-to-end proof of {@code ParadoxResolutionSaga}'s player-submission branch (sagas.md Saga 5,
 * timeline-mvp8-paradox-completion): players submitting a resolution card, the all-submitted close racing the
 * 2s test timer (application-test.yml), and a multi-paradox event where one paradox clears while another
 * (a permanent {@code SEAL_BREACH}) does not.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class})
class ParadoxResolutionPlayerSubmissionIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_RESOLUTION_PHASE_STARTED = "ParadoxResolutionPhaseStarted";
    private static final String PARADOX_RESOLVED = "ParadoxResolved";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
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
        collector.received.clear();
    }

    @Test
    void allPlayersSubmitClearingCards_resolvesBeforeTimerAndNeverDuplicates() throws InterruptedException {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        publishEraStarted(gameId, eraNumber, players);
        publishThreeOutcomeEvent(
                gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId, 60, secondOutcomeId, 25, thirdOutcomeId, 15);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        publishSpecialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));

        // Every player submits a SUPPRESS on the annihilated outcome — the cumulative effect (each applied in
        // turn against the prior submission's result) clears IMPOSSIBLE_ERASURE well before the 2s test timer.
        for (var playerId : players) {
            publishParadoxResolutionCardPlayed(
                    gameId, eraNumber, playerId, "SUPPRESS", paradoxedEventId, annihilatedOutcomeId);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_RESOLVED, OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));

        // Wait well past the 2s timer to prove the timer-expiry sweep, finding the phase already COMPLETED,
        // produces no duplicate facts.
        Thread.sleep(3000);
        var messages = messagesFor(gameId);
        assertThat(messages.stream().filter(m -> PARADOX_RESOLVED.equals(m.eventType())))
                .hasSize(1);
        assertThat(messages.stream().filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType())))
                .hasSize(1);
        assertThat(messages.stream().filter(m -> PARADOX_CASCADED.equals(m.eventType())))
                .isEmpty();
    }

    @Test
    void lateSubmissionAfterTimerAlreadyClosedThePhase_hasNoEffect() throws InterruptedException {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        publishEraStarted(gameId, eraNumber, players);
        publishThreeOutcomeEvent(
                gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId, 60, secondOutcomeId, 25, thirdOutcomeId, 15);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        publishSpecialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(PARADOX_RESOLUTION_PHASE_STARTED));

        // Only the first two players submit — the phase must be force-cascaded by the timer.
        publishParadoxResolutionCardPlayed(
                gameId, eraNumber, players.get(0), "PUSH", paradoxedEventId, annihilatedOutcomeId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_CASCADED, ERA_RESOLUTION_COMPLETED));

        // The last player's submission arrives after the phase already closed via timer expiry.
        publishParadoxResolutionCardPlayed(
                gameId, eraNumber, players.get(2), "PUSH", paradoxedEventId, annihilatedOutcomeId);
        Thread.sleep(2000);

        var messages = messagesFor(gameId);
        assertThat(messages.stream().filter(m -> PARADOX_CASCADED.equals(m.eventType())))
                .hasSize(1);
        assertThat(messages.stream().filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType())))
                .hasSize(1);
        assertThat(messages.stream().filter(m -> PARADOX_RESOLVED.equals(m.eventType())))
                .isEmpty();
    }

    @Test
    void multiParadoxEvent_impossibleErasureClearsButSealBreachPersists_cascadesEventExactlyOnce() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var sealedOutcomeId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID());

        publishEraStarted(gameId, eraNumber, players);
        // annihilatedOutcomeId holds the highest probability so ANNIHILATE-ing it also trips IMPOSSIBLE_ERASURE.
        publishThreeOutcomeEvent(
                gameId, eraNumber, paradoxedEventId, sealedOutcomeId, 30, annihilatedOutcomeId, 45, thirdOutcomeId, 25);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        // Seal one outcome, then breach it with a PUSH — SEAL_BREACH is permanent (nothing ever clears it).
        publishSpecialActionPlayed(gameId, eraNumber, paradoxedEventId, "SEAL", sealedOutcomeId);
        publishCardPlayed(gameId, eraNumber, paradoxedEventId, "PUSH", null, sealedOutcomeId);
        // Annihilate the highest-probability outcome too — IMPOSSIBLE_ERASURE, alongside the seal breach.
        publishSpecialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publishResolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        var paradoxDetected = messagesFor(gameId).stream()
                .filter(m -> PARADOX_DETECTED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        var paradoxes = (List<?>) paradoxDetected.get("paradoxes");
        assertThat(paradoxes).hasSize(2);

        // Both players submit a card that only clears IMPOSSIBLE_ERASURE (suppressing the annihilated outcome
        // does not touch the seal breach flag, which nothing can ever clear).
        for (var playerId : players) {
            publishParadoxResolutionCardPlayed(
                    gameId, eraNumber, playerId, "SUPPRESS", paradoxedEventId, annihilatedOutcomeId);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_RESOLVED, PARADOX_CASCADED, ERA_RESOLUTION_COMPLETED));

        var barrier = messagesFor(gameId).stream()
                .filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        var terminalResolutions = (List<?>) barrier.get("terminalResolutions");
        // Exactly one terminal entry for the event, despite it having two paradoxes.
        assertThat(terminalResolutions).hasSize(1);
        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) terminalResolutions.getFirst();
        assertThat(entry)
                .containsEntry("terminalState", "CASCADED")
                .containsEntry("eventId", paradoxedEventId.toString());
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

    /**
     * {@code EraStartedKafkaConsumer} runs in its own consumer group, independent of the group that processes
     * {@code ANNIHILATE}/{@code ResolutionStarted} — nothing otherwise guarantees the player roster is persisted
     * before a resolution phase opens and reads it.
     */
    private void awaitEraPlayersIndexed(UUID gameId, int eraNumber, int expectedPlayerCount) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT jsonb_array_length(player_ids) FROM era_players "
                                        + "WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isEqualTo(expectedPlayerCount));
    }

    private void publishEraStarted(UUID gameId, int eraNumber, List<UUID> playerIds) {
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
                        playerIds));
    }

    private void publishThreeOutcomeEvent(
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

    private void publishParadoxResolutionCardPlayed(
            UUID gameId, int eraNumber, UUID playerId, String cardType, UUID targetEventId, UUID targetOutcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("playerId", playerId);
        payload.put("cardInstanceId", UUID.randomUUID());
        payload.put("cardType", cardType);
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        publish(gameId, "ParadoxResolutionCardPlayed", payload);
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
}
