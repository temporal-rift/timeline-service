package io.github.temporalrift.timeline;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Test-only publisher of inbound {@code game.events} messages, carrying the envelope headers every
 * {@code GameEventIngestion} consumer requires — shared by the integration tests that drive a game through an
 * era so each one only spells out the payloads its scenario actually varies.
 */
@TestComponent
class GameEventsTestPublisher {

    private static final String GAME_EVENTS_TOPIC = "game.events";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    GameEventsTestPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    void eraStarted(UUID gameId, int eraNumber, List<UUID> playerIds) {
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

    /** One {@code EventsDrawn} event with three outcomes at the given probabilities, in the given order. */
    void threeOutcomeEventDrawn(
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
                                        outcome(outcomeIdA, "a", probabilityA),
                                        outcome(outcomeIdB, "b", probabilityB),
                                        outcome(outcomeIdC, "c", probabilityC))))));
    }

    void specialActionPlayed(
            UUID gameId, int eraNumber, UUID targetEventId, String specialAction, UUID targetOutcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", UUID.randomUUID());
        payload.put("faction", factionFor(specialAction));
        payload.put("specialAction", specialAction);
        payload.put("targetEventId", targetEventId);
        payload.put("targetOutcomeId", targetOutcomeId);
        payload.put("targetPlayerId", null);
        publish(gameId, "SpecialActionPlayed", payload);
    }

    /** temporal-rift-gdd.md §"Faction specials" — the faction each special action belongs to. */
    private static String factionFor(String specialAction) {
        return switch (specialAction) {
            case "ANNIHILATE", "CORRUPT", "CASCADE" -> "ERASERS";
            case "SEAL", "FORESIGHT", "FULFILLMENT" -> "PROPHETS";
            case "MIMIC", "REWRITE", "OBSCURE" -> "REVISIONISTS";
            case "THREAD", "TAPESTRY", "UNRAVEL" -> "WEAVERS";
            case "RALLY", "EXPOSE", "MOMENTUM" -> "ACTIVISTS";
            default -> throw new IllegalArgumentException("Unknown special action: " + specialAction);
        };
    }

    void cardPlayed(
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

    void paradoxResolutionCardPlayed(
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

    /**
     * timeline-mvp9-resolution-ordering-paradox-cards: {@code CardPlayed}/{@code SpecialActionPlayed} are
     * buffered, not applied immediately — a round's effects only take place once its {@code ActionRoundClosed}
     * triggers the priority-ordered replay.
     */
    void actionRoundClosed(UUID gameId, int eraNumber, int roundNumber) {
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

    void resolutionStarted(UUID gameId, int eraNumber, UUID eventId) {
        publish(gameId, "ResolutionStarted", Map.of("gameId", gameId, "eraNumber", eraNumber), eventId);
    }

    void publish(UUID gameId, String eventType, Object payload) {
        publish(gameId, eventType, payload, UUID.randomUUID());
    }

    void publish(UUID gameId, String eventType, Object payload, UUID eventId) {
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

    private static Map<String, Object> outcome(UUID outcomeId, String description, int initialProbability) {
        return Map.of("outcomeId", outcomeId, "description", description, "initialProbability", initialProbability);
    }
}
