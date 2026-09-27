package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scoring relies on each Annihilate's resolution fact reaching the game's partition before the era barrier. */
@TimelineServiceIntegrationTest
class AnnihilationResolvedIT {

    private static final String ANNIHILATION_RESOLVED = "AnnihilationResolved";
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
    void annihilateOnTheLeader_isPublishedBeforeTheEraResolutionBarrier() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var leaderId = UUID.randomUUID();
        publisher.threeOutcomeEventDrawn(
                gameId, 1, eventId, leaderId, 40, UUID.randomUUID(), 35, UUID.randomUUID(), 25);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM future_event_era_index WHERE game_id = ? AND era_number = ?",
                                Integer.class,
                                gameId,
                                1))
                        .isEqualTo(1));

        publisher.specialActionPlayed(gameId, 1, eventId, "ANNIHILATE", leaderId);
        publisher.actionRoundClosed(gameId, 1, 1);
        publisher.resolutionStarted(gameId, 1, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(collector.eventTypesFor(gameId)).contains(ERA_RESOLUTION_COMPLETED));
        var eventTypes = collector.eventTypesFor(gameId);
        assertThat(eventTypes).containsOnlyOnce(ANNIHILATION_RESOLVED);
        assertThat(eventTypes.indexOf(ANNIHILATION_RESOLVED)).isLessThan(eventTypes.indexOf(ERA_RESOLUTION_COMPLETED));
        var resolution = collector.messagesFor(gameId).stream()
                .filter(m -> ANNIHILATION_RESOLVED.equals(m.eventType()))
                .findFirst()
                .orElseThrow()
                .payload();
        assertThat(resolution).containsEntry("erased", true).containsEntry("wasLeading", true);
        assertThat(resolution.get("targetOutcomeId")).hasToString(leaderId.toString());
    }
}
