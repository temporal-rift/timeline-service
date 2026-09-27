package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Kafka-level proof that an Annihilate threatens the Weaver's pending link instead of guaranteeing it: a cleared
 * chain conflict and a Tapestry absorption both keep the chain unbroken, but only the drawn winner confirms a
 * link; and a pending link whose event cascades expires in its origin era so the next era's Thread is accepted.
 */
@TimelineServiceIntegrationTest
class WeaverChainConflictIT {

    private static final String CHAIN_LINK_THREADED = "ChainLinkThreaded";
    private static final String CHAIN_LINK_ADDED = "ChainLinkAdded";
    private static final String CHAIN_LINK_INVALIDATED = "ChainLinkInvalidated";
    private static final String CHAIN_COMPLETED = "ChainCompleted";
    private static final String CHAIN_BROKEN = "ChainBroken";
    private static final String CHAIN_PROTECTION_ARMED = "ChainProtectionArmed";
    private static final String CHAIN_PROTECTION_CONSUMED = "ChainProtectionConsumed";
    private static final String THREAD_REJECTED = "ThreadRejected";
    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";

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
    void stabilizedChainConflict_keepsChainButDrawnWinnerClearsTheLink() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        confirmTwoLinks(gameId, weaver);
        var event = UUID.randomUUID();
        var predicted = UUID.randomUUID();
        var survivor = UUID.randomUUID();
        drawEra(gameId, 3, weaver, event, predicted, 50, survivor, 50, UUID.randomUUID(), 0);

        weaverSpecial(gameId, 3, 1, weaver, "THREAD", event, predicted);
        awaitCount(gameId, CHAIN_LINK_THREADED, 3);
        publisher.specialActionPlayed(gameId, 3, event, "ANNIHILATE", predicted);
        publisher.actionRoundClosed(gameId, 3, 1);
        publisher.resolutionStarted(gameId, 3, UUID.randomUUID());
        awaitParadox(gameId, "CHAIN_CONFLICT");
        publisher.paradoxResolutionCardPlayed(gameId, 3, weaver, "STABILIZE", event, predicted);

        awaitWinner(gameId, event, survivor);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, CHAIN_LINK_INVALIDATED))
                        .singleElement()
                        .satisfies(payload -> {
                            assertThat(payload.get("invalidatedOutcomeId")).hasToString(predicted.toString());
                            assertThat(payload).containsEntry("chainLength", 2);
                        }));
        assertThat(payloadsOf(gameId, CHAIN_LINK_ADDED)).hasSize(2);
        assertThat(eventTypesOf(gameId)).doesNotContain(CHAIN_COMPLETED, CHAIN_BROKEN);
    }

    @Test
    void tapestryAbsorption_clearsTheLinkAndOnlyTheNextDrawnWinnerCompletesTheChain() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        confirmTwoLinks(gameId, weaver);
        var event = UUID.randomUUID();
        var predicted = UUID.randomUUID();
        var rethreaded = UUID.randomUUID();
        drawEra(gameId, 3, weaver, event, predicted, 50, rethreaded, 50, UUID.randomUUID(), 0);

        weaverSpecial(gameId, 3, 1, weaver, "TAPESTRY", null, null);
        awaitCount(gameId, CHAIN_PROTECTION_ARMED, 1);
        weaverSpecial(gameId, 3, 1, weaver, "THREAD", event, predicted);
        awaitCount(gameId, CHAIN_LINK_THREADED, 3);
        publisher.specialActionPlayed(gameId, 3, event, "ANNIHILATE", predicted);
        publisher.actionRoundClosed(gameId, 3, 1);
        awaitCount(gameId, CHAIN_PROTECTION_CONSUMED, 1);
        awaitCount(gameId, CHAIN_LINK_INVALIDATED, 1);
        assertThat(payloadsOf(gameId, CHAIN_LINK_ADDED)).hasSize(2);

        weaverSpecial(gameId, 3, 2, weaver, "THREAD", event, rethreaded);
        awaitCount(gameId, CHAIN_LINK_THREADED, 4);
        publisher.resolutionStarted(gameId, 3, UUID.randomUUID());

        awaitWinner(gameId, event, rethreaded);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, CHAIN_COMPLETED))
                        .singleElement()
                        .satisfies(completed -> assertThat(((List<?>) completed.get("links")).getLast())
                                .asString()
                                .contains(rethreaded.toString())));
        assertThat(eventTypesOf(gameId)).doesNotContain(PARADOX_DETECTED, THREAD_REJECTED, CHAIN_BROKEN);
    }

    @Test
    void pendingLinkOnCascadedEvent_expiresInItsEraSoTheCarriedEventCanBeThreadedAgain() {
        var gameId = UUID.randomUUID();
        var weaver = UUID.randomUUID();
        var event = UUID.randomUUID();
        var predicted = UUID.randomUUID();
        var rival = UUID.randomUUID();
        var third = UUID.randomUUID();
        drawEra(gameId, 1, weaver, event, predicted, 50, rival, 31, third, 19);

        weaverSpecial(gameId, 1, 1, weaver, "THREAD", event, predicted);
        awaitCount(gameId, CHAIN_LINK_THREADED, 1);
        // Collide ties the Thread-lifted prediction with its rival above the third outcome: a Dead Heat.
        publisher.cardPlayed(gameId, 1, event, "COLLIDE", predicted, rival);
        publisher.actionRoundClosed(gameId, 1, 1);
        publisher.resolutionStarted(gameId, 1, UUID.randomUUID());
        awaitParadox(gameId, "DEAD_HEAT");
        awaitCount(gameId, PARADOX_CASCADED, 1);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, CHAIN_LINK_INVALIDATED))
                        .singleElement()
                        .satisfies(payload -> assertThat(payload).containsEntry("eraNumber", 1)));

        publisher.eraStarted(gameId, 2, List.of(weaver));
        publisher.threeOutcomeEventDrawn(gameId, 2, event, "CASCADED", predicted, 42, rival, 42, third, 16);
        weaverSpecial(gameId, 2, 1, weaver, "THREAD", event, predicted);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, CHAIN_LINK_THREADED))
                        .anySatisfy(payload -> assertThat(payload).containsEntry("eraNumber", 2)));
        assertThat(eventTypesOf(gameId)).doesNotContain(THREAD_REJECTED, CHAIN_BROKEN, CHAIN_LINK_ADDED);
    }

    /**
     * Confirms era-1 and era-2 links. The losers are annihilated before the Thread so its ceiling-clamped push
     * cannot hand them weight, keeping each draw deterministic.
     */
    private void confirmTwoLinks(UUID gameId, UUID weaver) {
        for (var era : List.of(1, 2)) {
            var event = UUID.randomUUID();
            var winner = UUID.randomUUID();
            var second = UUID.randomUUID();
            var third = UUID.randomUUID();
            drawEra(gameId, era, weaver, event, winner, 100, second, 0, third, 0);
            publisher.specialActionPlayed(gameId, era, event, "ANNIHILATE", second);
            publisher.specialActionPlayed(gameId, era, event, "ANNIHILATE", third);
            publisher.actionRoundClosed(gameId, era, 1);
            weaverSpecial(gameId, era, 1, weaver, "THREAD", event, winner);
            awaitCount(gameId, CHAIN_LINK_THREADED, era);
            publisher.resolutionStarted(gameId, era, UUID.randomUUID());
            awaitWinner(gameId, event, winner);
            awaitCount(gameId, CHAIN_LINK_ADDED, era);
        }
    }

    private void drawEra(
            UUID gameId,
            int era,
            UUID weaver,
            UUID event,
            UUID first,
            int firstWeight,
            UUID second,
            int secondWeight,
            UUID third,
            int thirdWeight) {
        publisher.eraStarted(gameId, era, List.of(weaver));
        publisher.threeOutcomeEventDrawn(
                gameId, era, event, first, firstWeight, second, secondWeight, third, thirdWeight);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                era))
                        .isPositive());
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM era_players WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                era))
                        .isPositive());
    }

    private void weaverSpecial(
            UUID gameId, int era, int round, UUID weaver, String specialAction, UUID eventId, UUID outcomeId) {
        var payload = new HashMap<String, Object>();
        payload.put("gameId", gameId);
        payload.put("eraNumber", era);
        payload.put("roundNumber", round);
        payload.put("playerId", weaver);
        payload.put("faction", "WEAVERS");
        payload.put("specialAction", specialAction);
        payload.put("sourceEventId", null);
        payload.put("sourceOutcomeId", null);
        payload.put("targetEventId", eventId);
        payload.put("targetOutcomeId", outcomeId);
        payload.put("targetPlayerId", null);
        publisher.publish(gameId, "SpecialActionPlayed", payload);
    }

    private void awaitParadox(UUID gameId, String type) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, PARADOX_DETECTED))
                        .flatMap(payload -> (List<?>) payload.get("paradoxes"))
                        .extracting(paradox -> String.valueOf(((Map<?, ?>) paradox).get("type")))
                        .contains(type));
    }

    private void awaitWinner(UUID gameId, UUID eventId, UUID winnerId) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, OUTCOME_APPLIED))
                        .anySatisfy(payload -> {
                            assertThat(payload.get("eventId")).hasToString(eventId.toString());
                            assertThat(payload.get("winningOutcomeId")).hasToString(winnerId.toString());
                        }));
    }

    private void awaitCount(UUID gameId, String eventType, int expected) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(payloadsOf(gameId, eventType)).hasSize(expected));
    }

    private List<String> eventTypesOf(UUID gameId) {
        return collector.received.stream()
                .filter(m -> gameId.toString().equals(m.payload().get("gameId")))
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }

    private List<Map<String, Object>> payloadsOf(UUID gameId, String eventType) {
        return collector.received.stream()
                .filter(m -> gameId.toString().equals(m.payload().get("gameId")))
                .filter(m -> eventType.equals(m.eventType()))
                .map(TimelineEventsTestCollector.CollectedMessage::payload)
                .toList();
    }
}
