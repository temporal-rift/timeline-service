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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end proof of {@code ParadoxResolutionSaga}'s player-submission branch (sagas.md Saga 5,
 * timeline-mvp8-paradox-completion): players submitting a resolution card, the all-submitted close racing the
 * 2s test timer (application-test.yml), and a multi-paradox event where one paradox clears while another
 * (a permanent {@code SEAL_BREACH}) does not.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class, GameEventsTestPublisher.class})
class ParadoxResolutionPlayerSubmissionIT {

    private static final String PARADOX_DETECTED = "ParadoxDetected";
    private static final String PARADOX_RESOLUTION_PHASE_STARTED = "ParadoxResolutionPhaseStarted";
    private static final String PARADOX_RESOLVED = "ParadoxResolved";
    private static final String PARADOX_CASCADED = "ParadoxCascaded";
    private static final String OUTCOME_APPLIED = "OutcomeApplied";
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
    void allPlayersSubmitClearingCards_resolvesBeforeTimerAndNeverDuplicates() throws InterruptedException {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId, 60, secondOutcomeId, 25, thirdOutcomeId, 15);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_DETECTED, PARADOX_RESOLUTION_PHASE_STARTED));

        // Every player submits a SUPPRESS on the annihilated outcome — the cumulative effect (each applied in
        // turn against the prior submission's result) clears IMPOSSIBLE_ERASURE well before the 2s test timer.
        for (var playerId : players) {
            publisher.paradoxResolutionCardPlayed(
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

        publisher.eraStarted(gameId, eraNumber, players);
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId, 60, secondOutcomeId, 25, thirdOutcomeId, 15);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(eventTypesOf(messagesFor(gameId))).contains(PARADOX_RESOLUTION_PHASE_STARTED));

        // Only the first two players submit — the phase must be force-cascaded by the timer.
        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.get(0), "PUSH", paradoxedEventId, annihilatedOutcomeId);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(eventTypesOf(messagesFor(gameId)))
                        .contains(PARADOX_CASCADED, ERA_RESOLUTION_COMPLETED));

        // The last player's submission arrives after the phase already closed via timer expiry.
        publisher.paradoxResolutionCardPlayed(
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

        publisher.eraStarted(gameId, eraNumber, players);
        // annihilatedOutcomeId holds the highest probability so ANNIHILATE-ing it also trips IMPOSSIBLE_ERASURE.
        publisher.threeOutcomeEventDrawn(
                gameId, eraNumber, paradoxedEventId, sealedOutcomeId, 30, annihilatedOutcomeId, 45, thirdOutcomeId, 25);
        awaitFutureEventsIndexed(gameId, eraNumber, 1);
        awaitEraPlayersIndexed(gameId, eraNumber, players.size());

        // Seal one outcome, then breach it with a PUSH — SEAL_BREACH is permanent (nothing ever clears it).
        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "SEAL", sealedOutcomeId);
        publisher.cardPlayed(gameId, eraNumber, paradoxedEventId, "PUSH", null, sealedOutcomeId);
        // Annihilate the highest-probability outcome too — IMPOSSIBLE_ERASURE, alongside the seal breach.
        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
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
        assertThat(paradoxes).hasSize(2);

        // Both players submit a card that only clears IMPOSSIBLE_ERASURE (suppressing the annihilated outcome
        // does not touch the seal breach flag, which nothing can ever clear).
        for (var playerId : players) {
            publisher.paradoxResolutionCardPlayed(
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
     * before a resolution phase opens and reads it. A phase that opens without it stays open for its timer
     * instead ({@code ParadoxResolutionMissingRosterIT}); these tests want the all-submitted trigger, so they
     * wait for the roster first.
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

    private List<TimelineEventsTestCollector.CollectedMessage> messagesFor(UUID gameId) {
        return collector.messagesFor(gameId);
    }

    private static List<String> eventTypesOf(List<TimelineEventsTestCollector.CollectedMessage> messages) {
        return messages.stream()
                .map(TimelineEventsTestCollector.CollectedMessage::eventType)
                .toList();
    }
}
