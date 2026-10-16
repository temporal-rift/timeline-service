package io.github.temporalrift.timeline;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/** Builds {@code game.events} records in the messaging boundary's shape, as the simulation host would deliver them. */
public final class GameEventRecords {

    public static final String TOPIC = "game.events";

    private static final JsonMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    private GameEventRecords() {}

    public static Map<String, Object> eraStarted(UUID gameId, UUID eventId, int eraNumber, List<UUID> playerIds) {
        return record(
                gameId,
                "EraStarted",
                eventId,
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

    public static Map<String, Object> twoOutcomeEventDrawn(
            UUID gameId,
            UUID eventId,
            int eraNumber,
            UUID futureEventId,
            UUID outcomeA,
            int probabilityA,
            UUID outcomeB,
            int probabilityB) {
        return eventsDrawn(
                gameId,
                eventId,
                eraNumber,
                futureEventId,
                List.of(outcome(outcomeA, "a", probabilityA), outcome(outcomeB, "b", probabilityB)));
    }

    public static Map<String, Object> threeOutcomeEventDrawn(
            UUID gameId,
            UUID eventId,
            int eraNumber,
            UUID futureEventId,
            UUID outcomeA,
            int probabilityA,
            UUID outcomeB,
            int probabilityB,
            UUID outcomeC,
            int probabilityC) {
        return eventsDrawn(
                gameId,
                eventId,
                eraNumber,
                futureEventId,
                List.of(
                        outcome(outcomeA, "a", probabilityA),
                        outcome(outcomeB, "b", probabilityB),
                        outcome(outcomeC, "c", probabilityC)));
    }

    public static Map<String, Object> cardPlayed(
            UUID gameId, UUID eventId, int eraNumber, UUID targetEventId, String cardType, UUID targetOutcomeId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", eraNumber);
        payload.put("roundNumber", 1);
        payload.put("playerId", new UUID(gameId.getMostSignificantBits(), 7));
        payload.put("cardInstanceId", new UUID(gameId.getMostSignificantBits(), 8));
        payload.put("cardType", cardType);
        payload.put("grade", "II");
        payload.put("targetEventId", targetEventId);
        payload.put("sourceOutcomeId", null);
        payload.put("targetOutcomeId", targetOutcomeId);
        return record(gameId, "CardPlayed", eventId, payload);
    }

    public static Map<String, Object> actionRoundClosed(UUID gameId, UUID eventId, int eraNumber, int roundNumber) {
        return record(
                gameId,
                "ActionRoundClosed",
                eventId,
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

    private static Map<String, Object> eventsDrawn(
            UUID gameId, UUID eventId, int eraNumber, UUID futureEventId, List<Map<String, Object>> outcomes) {
        return record(
                gameId,
                "EventsDrawn",
                eventId,
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
                                outcomes))));
    }

    public static Map<String, Object> resolutionStarted(UUID gameId, UUID eventId, int eraNumber) {
        return record(gameId, "ResolutionStarted", eventId, Map.of("gameId", gameId, "eraNumber", eraNumber));
    }

    public static Map<String, Object> factionAssigned(UUID gameId, UUID eventId, UUID playerId, String faction) {
        return record(
                gameId, "FactionAssigned", eventId, Map.of("gameId", gameId, "playerId", playerId, "faction", faction));
    }

    public static Map<String, Object> record(UUID gameId, String eventType, UUID eventId, Object payload) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("eventId", eventId.toString());
        headers.put("aggregateId", gameId.toString());
        headers.put("aggregateType", "Game");
        headers.put("gameId", gameId.toString());
        headers.put("occurredAt", Instant.now().toString());
        headers.put("version", "1");
        headers.put("eventType", eventType);
        var record = new LinkedHashMap<String, Object>();
        record.put("topic", TOPIC);
        record.put("key", gameId.toString());
        record.put("headers", headers);
        record.put("payload", JSON.writeValueAsBytes(payload));
        return record;
    }

    private static Map<String, Object> outcome(UUID outcomeId, String description, int initialProbability) {
        return Map.of("outcomeId", outcomeId, "description", description, "initialProbability", initialProbability);
    }
}
