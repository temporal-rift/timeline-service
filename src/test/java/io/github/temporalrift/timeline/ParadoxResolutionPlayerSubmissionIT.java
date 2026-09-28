package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockingDetails;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Exercises player submission and timer-expiry races end to end. The timeout-processor spy uses a dedicated
 * context so the test can observe the scheduled no-op after an all-submitted close.
 */
@TimelineServiceIntegrationTest
class ParadoxResolutionPlayerSubmissionIT {

    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_RESOLUTION_PHASE_STARTED = "ParadoxResolutionPhaseStarted";
    private static final String PARADOX_RESOLVED = "ParadoxResolved";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";
    private static final String PARADOX_CARD_CONSUMER = "futureevent.paradox-resolution-card-played";
    private static final Duration NO_DUPLICATE_WINDOW = Duration.ofSeconds(3);

    @Autowired
    GameEventsTestPublisher publisher;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoSpyBean(name = "paradoxResolutionTimeoutProcessor")
    Object paradoxResolutionTimeoutProcessor;

    @BeforeEach
    void clearCollector() {
        collector.received.clear();
    }

    @Test
    void allPlayersSubmitClearingCards_resolvesBeforeTimerAndNeverDuplicates() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, firstOutcomeId, 50, secondOutcomeId, 31, thirdOutcomeId, 19);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        // A COLLIDE equalizing the leading pair trips DEAD_HEAT.
        publisher.cardPlayed(gameId, eraNumber, paradoxedEventId, "COLLIDE", firstOutcomeId, secondOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));

        // Every player submits a SUPPRESS breaking the tie — clearing DEAD_HEAT well before the 2s test timer.
        for (var playerId : players) {
            publisher.paradoxResolutionCardPlayed(
                    gameId, eraNumber, playerId, "SUPPRESS", paradoxedEventId, firstOutcomeId);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_RESOLVED, OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));

        awaitPhaseTimerExpired(gameId, eraNumber);
        awaitTimerExpiryAttempt(gameId, eraNumber);
        assertTerminalEventCountsRemainStable(gameId, 1, 0);
    }

    @Test
    void lateSubmissionAfterTimerAlreadyClosedThePhase_hasNoEffect() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, firstOutcomeId, 50, secondOutcomeId, 31, thirdOutcomeId, 19);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        // A COLLIDE equalizing the leading pair trips DEAD_HEAT.
        publisher.cardPlayed(gameId, eraNumber, paradoxedEventId, "COLLIDE", firstOutcomeId, secondOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(PARADOX_RESOLUTION_PHASE_STARTED));

        // Only the first player submits — a DETONATE moves no weight, so the dead heat persists and the
        // phase must be force-cascaded by the timer.
        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.get(0), "DETONATE", paradoxedEventId, secondOutcomeId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_CASCADED, ERA_RESOLUTION_COMPLETED));

        // The last player's submission arrives after the phase already closed via timer expiry.
        var lateSubmissionEventId = publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.get(2), "SUPPRESS", paradoxedEventId, secondOutcomeId);
        awaitEventProcessed(lateSubmissionEventId, PARADOX_CARD_CONSUMER);
        assertTerminalEventCountsRemainStable(gameId, 0, 1);
    }

    @Test
    void blockedPushAgainstSeal_recordsNoBreach_eventResolvesAfterTieClears() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var sealedOutcomeId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID());

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, sealedOutcomeId, 20, firstOutcomeId, 50, secondOutcomeId, 30);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        // Seal one outcome, then PUSH it — the blocked shift is an ordinary failure, so the only
        // paradox below comes from the Collide.
        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "SEAL", sealedOutcomeId);
        publisher.cardPlayed(gameId, eraNumber, paradoxedEventId, "PUSH", null, sealedOutcomeId);
        // A COLLIDE equalizing the leading pair trips DEAD_HEAT, the event's sole paradox.
        publisher.cardPlayed(gameId, eraNumber, paradoxedEventId, "COLLIDE", firstOutcomeId, secondOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        var paradoxDetected = messagesFor(gameId).stream()
                .filter(m -> PARADOX_DETECTED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        var paradoxes = (List<?>) paradoxDetected.get("paradoxes");
        assertThat(paradoxes).hasSize(1);

        // Both players submit a card that breaks the tie — with no other paradox in the way, the
        // event resolves normally instead of cascading.
        for (var playerId : players) {
            publisher.paradoxResolutionCardPlayed(
                    gameId, eraNumber, playerId, "SUPPRESS", paradoxedEventId, firstOutcomeId);
        }

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_RESOLVED, OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));

        var barrier = messagesFor(gameId).stream()
                .filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        var terminalResolutions = (List<?>) barrier.get("terminalResolutions");
        // Exactly one terminal entry for the event, resolving (not cascading) with a winning outcome.
        assertThat(terminalResolutions).hasSize(1);
        @SuppressWarnings("unchecked")
        var entry = (Map<String, Object>) terminalResolutions.getFirst();
        assertThat(entry)
                .containsEntry("terminalState", "OUTCOME_APPLIED")
                .containsEntry("eventId", paradoxedEventId.toString());
    }

    @Test
    void collideOnLeadingPair_raisesDeadHeat_suppressBreaksTieAndResolves() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var eventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID());

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, eventId, firstOutcomeId, 50, secondOutcomeId, 31, UUID.randomUUID(), 19);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        publisher.cardPlayed(gameId, eraNumber, eventId, "COLLIDE", firstOutcomeId, secondOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));
        var paradoxes = (List<?>) messagesFor(gameId).stream()
                .filter(m -> PARADOX_DETECTED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload()
                .get("paradoxes");
        assertThat(paradoxes).singleElement().satisfies(paradox -> {
            @SuppressWarnings("unchecked")
            var entry = (Map<String, Object>) paradox;
            assertThat(entry).containsEntry("type", "DEAD_HEAT");
            assertThat((List<?>) entry.get("affectedOutcomeIds"))
                    .map(Object::toString)
                    .containsExactlyInAnyOrder(firstOutcomeId.toString(), secondOutcomeId.toString());
        });

        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.getFirst(), "SUPPRESS", eventId, firstOutcomeId);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_RESOLVED, OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));
    }

    @Test
    void gradeOneSuppressTiesFreshLeaders_resolvesWithoutParadox() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var eventId = UUID.randomUUID();
        var leaderOutcomeId = UUID.randomUUID();

        publisher.eraStarted(gameId, eraNumber, List.of(UUID.randomUUID()));
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, eventId, UUID.randomUUID(), 33, UUID.randomUUID(), 33, leaderOutcomeId, 34);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);

        publisher.cardPlayed(gameId, eraNumber, eventId, "SUPPRESS", "I", null, leaderOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(OUTCOME_APPLIED, ERA_RESOLUTION_COMPLETED));
        assertThat(eventTypesOf(messagesFor(gameId))).doesNotContain(PARADOX_DETECTED);
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

    private void awaitPhaseTimerExpired(UUID gameId, int eraNumber) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT timer_expires_at <= CURRENT_TIMESTAMP FROM paradox_resolution_phase "
                                        + "WHERE game_id = ? AND era_number = ?",
                                Boolean.class,
                                gameId,
                                eraNumber))
                        .isTrue());
    }

    private void awaitTimerExpiryAttempt(UUID gameId, int eraNumber) {
        var sagaId = jdbcTemplate.queryForObject(
                "SELECT saga_id FROM paradox_resolution_phase WHERE game_id = ? AND era_number = ?",
                UUID.class,
                gameId,
                eraNumber);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(mockingDetails(paradoxResolutionTimeoutProcessor)
                                .getInvocations())
                        .anyMatch(invocation -> invocation.getMethod().getName().equals("handleTimerExpiry")
                                && sagaId.equals(invocation.getArgument(0))));
    }

    private void awaitEventProcessed(UUID eventId, String consumer) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                                Integer.class,
                                eventId,
                                consumer))
                        .isEqualTo(1));
    }

    private void assertTerminalEventCountsRemainStable(
            UUID gameId, int expectedResolvedCount, int expectedCascadedCount) {
        await().during(NO_DUPLICATE_WINDOW)
                .atMost(NO_DUPLICATE_WINDOW.plusSeconds(2))
                .untilAsserted(() -> {
                    var messages = messagesFor(gameId);
                    assertThat(messages.stream().filter(m -> PARADOX_RESOLVED.equals(m.eventType())))
                            .hasSize(expectedResolvedCount);
                    assertThat(messages.stream().filter(m -> PARADOX_CASCADED.equals(m.eventType())))
                            .hasSize(expectedCascadedCount);
                    assertThat(messages.stream().filter(m -> ERA_RESOLUTION_COMPLETED.equals(m.eventType())))
                            .hasSize(1);
                });
    }

    private List<TimelineEventsTestCollector.CollectedMessage> messagesFor(UUID gameId) {
        return collector.messagesFor(gameId);
    }

    private static List<String> eventTypesOf(List<TimelineEventsTestCollector.CollectedMessage> messages) {
        return messages.stream()
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }
}
