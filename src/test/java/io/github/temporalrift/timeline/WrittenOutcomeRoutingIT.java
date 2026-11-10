package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;

/**
 * A Foresight's writing survives the event store between rounds, routes a later Annihilate's erased weight with the
 * default rule, and a redelivered round close changes nothing.
 */
@TimelineServiceIntegrationTest
class WrittenOutcomeRoutingIT {

    private static final String ANNIHILATION_RESOLVED = "AnnihilationResolved";
    private static final String ERA_RESOLUTION_COMPLETED = "EraResolutionCompleted";

    @Autowired
    GameEventsTestPublisher publisher;

    @Autowired
    TimelineEventsTestCollector collector;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    FutureEventRepository futureEvents;

    @BeforeEach
    void clearCollector() {
        collector.received.clear();
    }

    @Test
    void foresightInRoundOne_routesRoundTwoErasure_andRedeliveryChangesNothing() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var o1 = UUID.randomUUID();
        var o2 = UUID.randomUUID();
        var o3 = UUID.randomUUID();
        publisher.threeOutcomeEventDrawn(gameId, 1, eventId, o1, 50, o2, 30, o3, 20);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                1))
                        .isEqualTo(1));

        publisher.specialActionPlayed(gameId, 1, 1, eventId, "FORESIGHT", o3);
        publisher.actionRoundClosed(gameId, 1, 1);
        publisher.specialActionPlayed(gameId, 1, 2, eventId, "ANNIHILATE", o1);
        var roundTwoClosed = publisher.actionRoundClosed(gameId, 1, 2);
        publisher.publish(
                gameId,
                "ActionRoundClosed",
                Map.of(
                        "gameId", gameId,
                        "eraNumber", 1,
                        "roundNumber", 2,
                        "closedReason", "ALL_SUBMITTED",
                        "totalActions", 1),
                roundTwoClosed);
        publisher.resolutionStarted(gameId, 1, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(ERA_RESOLUTION_COMPLETED));
        assertThat(futureEvents.findById(eventId).outcomes())
                .extracting(Outcome::probability)
                .containsExactly(0, 30, 70);
        assertThat(collector.eventTypesFor(gameId))
                .containsOnlyOnce(ANNIHILATION_RESOLVED)
                .doesNotContain("OutcomeWritten");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_store WHERE aggregate_id = ? AND event_type = 'OutcomeWritten'",
                        Integer.class,
                        eventId))
                .isEqualTo(1);
    }
}
