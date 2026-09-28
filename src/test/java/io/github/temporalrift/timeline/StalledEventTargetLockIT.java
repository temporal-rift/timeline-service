package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
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
