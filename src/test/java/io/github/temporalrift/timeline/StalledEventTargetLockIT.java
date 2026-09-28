package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;

@TimelineServiceIntegrationTest
class StalledEventTargetLockIT {

    @Autowired
    GameEventsTestPublisher publisher;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    FutureEventRepository futureEvents;

    @Autowired
    CascadeCarryForwardPort cascadeCarryForward;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EraPlayersPort eraPlayers;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void roundClose_waitsForDraftingTransaction_thenAppliesStall() throws Exception {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(listeners.getListenerContainers())
                .filteredOn(container ->
                        List.of(container.getContainerProperties().getTopics()).contains("game.events"))
                .extracting(container -> container.getContainerProperties().getGroupId())
                .containsExactlyInAnyOrder(
                        "timeline-service.futureevent.game-events", "timeline-service.membership.faction-assigned");

        // Block drafting inside its transaction without adding a second Spring test context or mocking persistence.
        try (var blocker = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE future_event_era_index IN ACCESS EXCLUSIVE MODE");
            }
            try {
                publisher.eraStarted(gameId, 1, players);
                publisher.threeOutcomeEventDrawn(
                        gameId, 1, eventId, UUID.randomUUID(), 50, UUID.randomUUID(), 30, UUID.randomUUID(), 20);
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                        "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                                + "AND query LIKE '%future_event_era_index%'",
                                        Integer.class))
                                .isPositive());
                assertThat(eraPlayers.find(gameId, 1)).contains(players);
                publisher.cardPlayed(gameId, 1, eventId, "STALL", null, null);
                var roundClosedId = publisher.actionRoundClosed(gameId, 1, 1);

                await().during(Duration.ofSeconds(2))
                        .atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> {
                            assertThat(jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                                            Integer.class,
                                            "futureevent.action-round-closed",
                                            roundClosedId))
                                    .isZero();
                            assertThat(jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM round_action_buffer WHERE game_id = ?",
                                            Integer.class,
                                            gameId))
                                    .isZero();
                        });
            } finally {
                blocker.rollback();
            }
        }
        await().atMost(Duration.ofSeconds(30))
                .ignoreException(FutureEventNotFoundException.class)
                .untilAsserted(() ->
                        assertThat(futureEvents.findById(eventId).stalled()).isTrue());
    }

    @Test
    void laterRoundAnnihilateAndCascade_onPersistedStalledEvent_produceNoEffectsOrConfirmations() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();
        publisher.threeOutcomeEventDrawn(
                gameId, 1, eventId, leaderId, 50, UUID.randomUUID(), 30, UUID.randomUUID(), 20);
        publisher.cardPlayed(gameId, 1, eventId, "STALL", null, null);
        publisher.actionRoundClosed(gameId, 1, 1);
        await().atMost(Duration.ofSeconds(30))
                .ignoreException(FutureEventNotFoundException.class)
                .untilAsserted(() ->
                        assertThat(futureEvents.findById(eventId).stalled()).isTrue());

        publishRoundTwoSpecial(gameId, eventId, leaderId, "ANNIHILATE");
        publishRoundTwoSpecial(gameId, eventId, leaderId, "CASCADE");
        publisher.actionRoundClosed(gameId, 1, 2);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains("AdjustedBandsPublished"));

        var stalled = futureEvents.findById(eventId);
        assertThat(stalled.stalled()).isTrue();
        assertThat(stalled.outcomes()).extracting(Outcome::probability).containsExactly(50, 30, 20);
        assertThat(stalled.outcomes()).allMatch(o -> !o.annihilated() && !o.sealed());
        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(collector.eventTypesFor(gameId)).doesNotContain("AnnihilationResolved", "SpecialRejected");
    }

    private void publishRoundTwoSpecial(UUID gameId, UUID eventId, UUID outcomeId, String special) {
        publisher.publish(
                gameId,
                "SpecialActionPlayed",
                Map.of(
                        "gameId",
                        gameId,
                        "eraNumber",
                        1,
                        "roundNumber",
                        2,
                        "playerId",
                        UUID.randomUUID(),
                        "faction",
                        "ERASERS",
                        "specialAction",
                        special,
                        "targetEventId",
                        eventId,
                        "targetOutcomeId",
                        outcomeId));
    }
}
