package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end proof that a resolution phase opening before its era's player roster has been persisted
 * (issue #41: {@code EraStartedKafkaConsumer} consumes {@code game.events} in its own consumer group, unordered
 * against the group that opens phases) neither closes on its first submission nor drops the rest.
 *
 * <p>Runs with a longer resolution timer than the rest of the suite so the assertions are about the
 * all-submitted trigger rather than about racing a 2s timer.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "game.rules.paradox-resolution-timer-seconds=15")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class, GameEventsTestPublisher.class})
class ParadoxResolutionMissingRosterIT {

    private static final String PARADOX_RESOLUTION_PHASE_STARTED = "ParadoxResolutionPhaseStarted";
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
    void phaseOpenedWithoutARoster_recordsEverySubmissionAndClosesOnlyOnTimerExpiry() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        // No EraStarted at all: the roster never lands, so the phase opens — and stays — roster-unknown.
        openParadoxResolutionPhase(gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId);
        assertThat(pendingPlayerIdsOf(gameId, eraNumber)).isNull();

        players.forEach(playerId -> publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, playerId, "SUPPRESS", paradoxedEventId, annihilatedOutcomeId));

        // Every submission is recorded against the still-open phase — under the bug this fix addresses, the
        // first one would have closed it and the other two would have been dropped.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(recordedSubmissionCountOf(gameId, eraNumber)).isEqualTo(players.size()));
        assertThat(collector.eventTypesFor(gameId)).doesNotContain(ERA_RESOLUTION_COMPLETED);

        // The timer is the phase's only close trigger while the roster is unknown.
        await().atMost(Duration.ofSeconds(40))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(ERA_RESOLUTION_COMPLETED));
        assertThat(collector.eventTypesFor(gameId).stream()
                        .filter(ERA_RESOLUTION_COMPLETED::equals)
                        .toList())
                .hasSize(1);
        assertThat(recordedSubmissionCountOf(gameId, eraNumber)).isEqualTo(players.size());
    }

    @Test
    void rosterArrivingAfterThePhaseOpened_isAdoptedAndRestoresTheAllSubmittedClose() {
        var gameId = UUID.randomUUID();
        var eraNumber = 1;
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        openParadoxResolutionPhase(gameId, eraNumber, paradoxedEventId, annihilatedOutcomeId);
        assertThat(pendingPlayerIdsOf(gameId, eraNumber)).isNull();

        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.getFirst(), "SUPPRESS", paradoxedEventId, annihilatedOutcomeId);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(recordedSubmissionCountOf(gameId, eraNumber)).isEqualTo(1));

        // The late EraStarted: the next submission adopts the roster, leaving pending only the players who
        // have not submitted yet.
        publisher.eraStarted(gameId, eraNumber, players);
        awaitEraPlayersIndexed(gameId, eraNumber);
        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.get(1), "SUPPRESS", paradoxedEventId, annihilatedOutcomeId);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(pendingPlayerIdsOf(gameId, eraNumber)).isEqualTo(1));

        // With the roster known, the last submission closes the phase — well inside the 15s timer.
        publisher.paradoxResolutionCardPlayed(
                gameId, eraNumber, players.get(2), "SUPPRESS", paradoxedEventId, annihilatedOutcomeId);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(ERA_RESOLUTION_COMPLETED));
        assertThat(recordedSubmissionCountOf(gameId, eraNumber)).isEqualTo(players.size());
    }

    /** Drives an era far enough to detect a paradox and open its resolution phase, without any {@code EraStarted}. */
    private void openParadoxResolutionPhase(
            UUID gameId, int eraNumber, UUID paradoxedEventId, UUID annihilatedOutcomeId) {
        publisher.threeOutcomeEventDrawn(
                gameId,
                eraNumber,
                paradoxedEventId,
                annihilatedOutcomeId,
                60,
                UUID.randomUUID(),
                25,
                UUID.randomUUID(),
                15);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isEqualTo(1));

        // Annihilating the highest-probability outcome trips IMPOSSIBLE_ERASURE.
        publisher.specialActionPlayed(gameId, eraNumber, paradoxedEventId, "ANNIHILATE", annihilatedOutcomeId);
        publisher.actionRoundClosed(gameId, eraNumber, 1);
        publisher.resolutionStarted(gameId, eraNumber, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(collector.eventTypesFor(gameId)).contains(PARADOX_RESOLUTION_PHASE_STARTED));
    }

    private void awaitEraPlayersIndexed(UUID gameId, int eraNumber) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM era_players WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                eraNumber))
                        .isEqualTo(1));
    }

    /** {@code null} while the phase's roster is unknown, otherwise how many players are still pending. */
    private Integer pendingPlayerIdsOf(UUID gameId, int eraNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(pending_player_ids) FROM paradox_resolution_phase "
                        + "WHERE game_id = ? AND era_number = ?",
                Integer.class,
                gameId,
                eraNumber);
    }

    private Integer recordedSubmissionCountOf(UUID gameId, int eraNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(submissions) FROM paradox_resolution_phase "
                        + "WHERE game_id = ? AND era_number = ?",
                Integer.class,
                gameId,
                eraNumber);
    }
}
