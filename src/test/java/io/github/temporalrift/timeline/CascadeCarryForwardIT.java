package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of the eraser-cascade-erasure capability: an outcome erased and CASCADE-armed in one era,
 * whose event independently carries into the next era, is re-erased there exactly once when that era's redraw
 * is consumed.
 */
@TimelineServiceIntegrationTest
class CascadeCarryForwardIT {

    private static final String CASCADE_CARRIED_FORWARD = "CascadeCarriedForward";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";

    @Autowired
    GameEventsTestPublisher publisher;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCollector() {
        collector.received.clear();
    }

    @Test
    void cascadeArmedAgainstAnErasureThatCarriesForward_reappliesTheErasureExactlyOnceNextEra() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var erasedOutcomeId = UUID.randomUUID();

        driveEra1ToStallWithAnArmedCascade(gameId, eventId, erasedOutcomeId);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT era_number FROM cascade_carry_forward WHERE event_id = ? AND outcome_id = ?",
                        Integer.class,
                        eventId,
                        erasedOutcomeId))
                .isEqualTo(2);

        publisher.threeOutcomeEventDrawn(
                gameId, 2, eventId, "STALLED", erasedOutcomeId, 40, UUID.randomUUID(), 35, UUID.randomUUID(), 25);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(CASCADE_CARRIED_FORWARD));
        var applied = collector.messagesFor(gameId).stream()
                .filter(m -> CASCADE_CARRIED_FORWARD.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        assertThat(applied).containsEntry("eraNumber", 2);
        assertThat(applied.get("targetEventId")).hasToString(eventId.toString());
        assertThat(applied.get("targetOutcomeId")).hasToString(erasedOutcomeId.toString());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM cascade_carry_forward WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    void redeliveredEraStart_appliesTheCarryForwardOnlyOnce() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var erasedOutcomeId = UUID.randomUUID();
        var eraTwoDrawEnvelopeId = UUID.randomUUID();

        driveEra1ToStallWithAnArmedCascade(gameId, eventId, erasedOutcomeId);

        publisher.publish(
                gameId, "EventsDrawn", eraTwoRedrawPayload(gameId, eventId, erasedOutcomeId), eraTwoDrawEnvelopeId);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(CASCADE_CARRIED_FORWARD));

        // Redelivered with the same envelope eventId — GameEventIngestion's idempotency claim must reject it
        // before applyPendingCascades ever runs a second time.
        publisher.publish(
                gameId, "EventsDrawn", eraTwoRedrawPayload(gameId, eventId, erasedOutcomeId), eraTwoDrawEnvelopeId);

        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.messagesFor(gameId).stream()
                                .filter(m -> CASCADE_CARRIED_FORWARD.equals(m.eventType()))
                                .count())
                        .isEqualTo(1));
    }

    /** Drafts one event, arms a CASCADE against an outcome ANNIHILATEd in the same round, and STALLs the event
     * so it carries into era 2 — the only way anything is left for the carry-forward to apply to. */
    private void driveEra1ToStallWithAnArmedCascade(UUID gameId, UUID eventId, UUID erasedOutcomeId) {
        publisher.threeOutcomeEventDrawn(
                gameId, 1, eventId, erasedOutcomeId, 40, UUID.randomUUID(), 35, UUID.randomUUID(), 25);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                1))
                        .isEqualTo(1));

        publisher.specialActionPlayed(gameId, 1, eventId, "ANNIHILATE", erasedOutcomeId);
        publisher.specialActionPlayed(gameId, 1, eventId, "CASCADE", erasedOutcomeId);
        publisher.cardPlayed(gameId, 1, eventId, "STALL", null, null);
        publisher.actionRoundClosed(gameId, 1, 1);
        publisher.resolutionStarted(gameId, 1, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(ERA_RESOLUTION_COMPLETED));
    }

    private static Map<String, Object> eraTwoRedrawPayload(UUID gameId, UUID eventId, UUID erasedOutcomeId) {
        return Map.of(
                "gameId",
                gameId,
                "eraNumber",
                2,
                "events",
                List.of(Map.of(
                        "eventId",
                        eventId,
                        "title",
                        "Test Future Event",
                        "carryOverState",
                        "STALLED",
                        "outcomes",
                        List.of(
                                outcome(erasedOutcomeId, "a", 40),
                                outcome(UUID.randomUUID(), "b", 35),
                                outcome(UUID.randomUUID(), "c", 25)))));
    }

    private static Map<String, Object> outcome(UUID outcomeId, String description, int initialProbability) {
        return Map.of("outcomeId", outcomeId, "description", description, "initialProbability", initialProbability);
    }
}
