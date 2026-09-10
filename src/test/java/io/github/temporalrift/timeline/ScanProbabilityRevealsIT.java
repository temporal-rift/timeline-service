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
 * End-to-end proof that a list-mode SCAN's {@code ProbabilityStateRevealed} is published at the round it was
 * played, at a later round with no buffered actions, and again at a further round while still active; agrees
 * with the banded state published at the same round close; stops after {@code EraEnded} cleans up its
 * entitlement; never mutates the scanned event's probabilities; is not duplicated by a redelivered
 * {@code ActionRoundClosed}; and is cleaned up by a direct {@code GameEnded} with no preceding {@code EraEnded}.
 */
@TimelineServiceIntegrationTest
class ScanProbabilityRevealsIT {

    private static final String GAME_EVENTS_TOPIC = "game.events";
    private static final String PROBABILITY_STATE_REVEALED = "ProbabilityStateRevealed";
    private static final String BANDED_PROBABILITY_PUBLISHED = "BandedProbabilityPublished";

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
    void listModeScan_revealsAtItsRoundAndALaterEmptyRound_thenStopsAfterEraEnded() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var winnerOutcomeId = UUID.randomUUID();
        var loserOutcomeId = UUID.randomUUID();
        var scanningPlayerId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, 70, loserOutcomeId, 30);
        awaitFutureEventIndexed(gameId, eraNumber);

        publishScanCardPlayed(gameId, eraNumber, 1, scanningPlayerId, List.of(futureEventId));
        publishActionRoundClosed(gameId, eraNumber, 1);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(PROBABILITY_STATE_REVEALED));

        var firstReveal = revealFor(gameId, futureEventId);
        assertThat(firstReveal).containsEntry("roundNumber", 1).containsEntry("playerId", scanningPlayerId.toString());
        assertOutcomeProbabilities(firstReveal, winnerOutcomeId, 70, loserOutcomeId, 30);

        // Round 2 closes with no buffered actions at all — the entitlement earned in round 1 must still
        // reveal current state.
        publishActionRoundClosed(gameId, eraNumber, 2);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.messagesFor(gameId).stream()
                                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                                .count())
                        .isEqualTo(2));

        var secondReveal = revealFor(gameId, futureEventId, 2);
        assertThat(secondReveal).containsEntry("roundNumber", 2);
        assertOutcomeProbabilities(secondReveal, winnerOutcomeId, 70, loserOutcomeId, 30);

        // Round 2's exact reveal and the public banded state published at the same round close must derive
        // from identical underlying probabilities: 70% bands HIGH and 30% bands LOW under this profile's
        // configured band-low-max/band-medium-max (30/60).
        var bandedPayload = collector.messagesFor(gameId).stream()
                .filter(m -> BANDED_PROBABILITY_PUBLISHED.equals(m.eventType()))
                .map(TimelineEventsTestCollector.CollectedMessage::payload)
                .findFirst()
                .orElseThrow();
        assertBand(bandedPayload, futureEventId, winnerOutcomeId, "HIGH");
        assertBand(bandedPayload, futureEventId, loserOutcomeId, "LOW");

        // SCAN never mutates: the scanned event's own probabilities are untouched by any of the above.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_store WHERE aggregate_id = ? AND event_type = 'ProbabilityShifted'",
                        Integer.class,
                        futureEventId))
                .isZero();

        // Round 3 closes while the entitlement is still active (era has not ended) — it must keep revealing.
        publishActionRoundClosed(gameId, eraNumber, 3);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.messagesFor(gameId).stream()
                                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                                .count())
                        .isEqualTo(3));
        var thirdReveal = revealFor(gameId, futureEventId, 3);
        assertOutcomeProbabilities(thirdReveal, winnerOutcomeId, 70, loserOutcomeId, 30);

        publishEraEnded(gameId, eraNumber);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scan_entitlement WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isZero());

        // A further round close in the same era must not resurrect a reveal for the now-cleaned-up entitlement.
        publishActionRoundClosed(gameId, eraNumber, 4);
        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.messagesFor(gameId).stream()
                                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                                .count())
                        .isEqualTo(3));
    }

    @Test
    void redeliveredActionRoundClosed_doesNotDuplicateTheReveal() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var winnerOutcomeId = UUID.randomUUID();
        var loserOutcomeId = UUID.randomUUID();
        var scanningPlayerId = UUID.randomUUID();
        var actionRoundClosedEventId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, 55, loserOutcomeId, 45);
        awaitFutureEventIndexed(gameId, eraNumber);
        publishScanCardPlayed(gameId, eraNumber, 1, scanningPlayerId, List.of(futureEventId));
        publishActionRoundClosed(gameId, eraNumber, 1, actionRoundClosedEventId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(PROBABILITY_STATE_REVEALED));
        var countAfterFirstDelivery = collector.messagesFor(gameId).stream()
                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                .count();
        assertThat(countAfterFirstDelivery).isEqualTo(1);

        // Same envelope eventId — must be claimed-and-skipped, not replayed a second time.
        publishActionRoundClosed(gameId, eraNumber, 1, actionRoundClosedEventId);

        await().pollDelay(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.messagesFor(gameId).stream()
                                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                                .count())
                        .isEqualTo(countAfterFirstDelivery));
    }

    @Test
    void gameEnded_deletesEntitlementsWithoutAnEraEndedFirst_andIsIdempotent() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var futureEventId = UUID.randomUUID();
        var winnerOutcomeId = UUID.randomUUID();
        var loserOutcomeId = UUID.randomUUID();
        var scanningPlayerId = UUID.randomUUID();

        publishEraStarted(gameId, eraNumber);
        publishEventsDrawn(gameId, eraNumber, futureEventId, winnerOutcomeId, 60, loserOutcomeId, 40);
        awaitFutureEventIndexed(gameId, eraNumber);
        publishScanCardPlayed(gameId, eraNumber, 1, scanningPlayerId, List.of(futureEventId));
        publishActionRoundClosed(gameId, eraNumber, 1);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scan_entitlement WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isEqualTo(1));

        // A final game can end with no EraEnded at all — GameEnded alone must remove the entitlement.
        publishGameEnded(gameId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scan_entitlement WHERE game_id = ?", Integer.class, gameId))
                        .isZero());

        // A second GameEnded for the same game, with nothing left to remove, must not error and must leave
        // the table empty.
        publishGameEnded(gameId);

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM scan_entitlement WHERE game_id = ?", Integer.class, gameId))
                        .isZero());
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

    private Map<String, Object> revealFor(UUID gameId, UUID eventId) {
        return revealFor(gameId, eventId, 1);
    }

    private Map<String, Object> revealFor(UUID gameId, UUID eventId, int roundNumber) {
        return collector.messagesFor(gameId).stream()
                .filter(m -> PROBABILITY_STATE_REVEALED.equals(m.eventType()))
                .map(TimelineEventsTestCollector.CollectedMessage::payload)
                .filter(p -> eventId.toString().equals(p.get("eventId"))
                        && Integer.valueOf(roundNumber).equals(p.get("roundNumber")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void assertBand(
            Map<String, Object> bandedPayload, UUID eventId, UUID outcomeId, String expectedBand) {
        var eventStates = (List<Map<String, Object>>) bandedPayload.get("eventStates");
        var eventState = eventStates.stream()
                .filter(e -> eventId.toString().equals(e.get("eventId")))
                .findFirst()
                .orElseThrow();
        var outcomes = (List<Map<String, Object>>) eventState.get("outcomes");
        var outcome = outcomes.stream()
                .filter(o -> outcomeId.toString().equals(o.get("outcomeId")))
                .findFirst()
                .orElseThrow();
        assertThat(outcome).containsEntry("band", expectedBand);
    }

    @SuppressWarnings("unchecked")
    private static void assertOutcomeProbabilities(
            Map<String, Object> revealPayload, UUID outcomeId1, int probability1, UUID outcomeId2, int probability2) {
        var outcomes = (List<Map<String, Object>>) revealPayload.get("outcomes");
        var byId = outcomes.stream()
                .collect(
                        java.util.stream.Collectors.toMap(o -> (String) o.get("outcomeId"), o -> o.get("probability")));
        assertThat(byId)
                .containsEntry(outcomeId1.toString(), probability1)
                .containsEntry(outcomeId2.toString(), probability2);
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
                                "carryOverState",
                                "FRESH",
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

    private void publishScanCardPlayed(
            UUID gameId, int eraNumber, int roundNumber, UUID playerId, List<UUID> targetEventIds) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", roundNumber);
        payload.put("playerId", playerId);
        payload.put("cardInstanceId", UUID.randomUUID());
        payload.put("cardType", "SCAN");
        payload.put("grade", targetEventIds.size() == 1 ? "I" : targetEventIds.size() == 2 ? "II" : "III");
        payload.put("targetEventIds", targetEventIds);
        publish(gameId, "CardPlayed", payload);
    }

    private void publishActionRoundClosed(UUID gameId, int eraNumber, int roundNumber) {
        publishActionRoundClosed(gameId, eraNumber, roundNumber, UUID.randomUUID());
    }

    private void publishActionRoundClosed(UUID gameId, int eraNumber, int roundNumber, UUID eventId) {
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
                        1),
                eventId);
    }

    private void publishEraEnded(UUID gameId, int eraNumber) {
        publish(
                gameId,
                "EraEnded",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        eraNumber,
                        "cascadedParadoxCount",
                        0,
                        "nextEraNumber",
                        eraNumber + 1));
    }

    private void publishGameEnded(UUID gameId) {
        publish(gameId, "GameEnded", Map.of("gameId", gameId, "endReason", "COLLAPSE", "finalScores", List.of()));
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
}
