package io.github.temporalrift.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;

/** A game played through the in-memory boundary: records in through the delivery bean, published facts out the sink. */
class InMemoryRuntimeTest {

    private static final String LIFECYCLE_GROUP = "timeline-service.futureevent.game-events";
    private static final String FACTION_GROUP = "timeline-service.membership.faction-assigned";
    private static final JsonMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    private final List<Map<String, Object>> published = new CopyOnWriteArrayList<>();

    @Test
    @DisplayName("an era resolved under in-memory reaches the sink on timeline.events in outbox order")
    void resolvedEra_isRelayedToTheSink() {
        try (var host = InMemoryTimelineHost.start(published::add).configureExecution()) {
            var gameId = UUID.randomUUID();
            deliverEra(host, gameId);

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(eventTypes(gameId))
                            .contains("ProbabilityStateCalculated", "OutcomeApplied", "EraResolutionCompleted"));

            var types = eventTypes(gameId);
            assertThat(types.indexOf("ProbabilityStateCalculated")).isLessThan(types.indexOf("OutcomeApplied"));
            assertThat(types.indexOf("OutcomeApplied")).isLessThan(types.indexOf("EraResolutionCompleted"));
            var first = published.getFirst();
            assertThat(first).containsEntry("topic", "timeline.events").containsEntry("key", gameId.toString());
            assertThat(headers(first))
                    .containsKeys("eventType", "eventId", "aggregateId", "aggregateType", "gameId", "occurredAt")
                    .containsEntry("gameId", gameId.toString())
                    .doesNotContainKeys("id", "timestamp");
            assertThat(JSON.readValue((byte[]) first.get("payload"), Map.class))
                    .containsEntry("gameId", gameId.toString());
        }
    }

    @Test
    @DisplayName("a record delivered twice runs its handler once")
    void duplicateDelivery_runsTheHandlerOnce() {
        try (var host = InMemoryTimelineHost.start(published::add).configureExecution()) {
            var gameId = UUID.randomUUID();
            deliverEra(host, gameId);
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(eventTypes(gameId)).contains("EraResolutionCompleted"));
            var before = published.size();

            // Same eventId as the original ResolutionStarted: the idempotency claim must turn it away.
            host.delivery().accept(GameEventRecords.resolutionStarted(gameId, resolutionEventId(gameId), 1));

            await().pollDelay(Duration.ofMillis(500))
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(published).hasSize(before));
        }
    }

    @Test
    @DisplayName("watermarks report each group one record further per handled record, and unknown topics are ignored")
    void watermarks_followDelivery() {
        try (var host = InMemoryTimelineHost.start(published::add).configureExecution()) {
            var watermarks = host.bean(SourceWatermarks.class);
            assertThat(watermarks.current())
                    .extracting(w -> w.groupId() + "|" + w.nextOffset())
                    .containsExactly(LIFECYCLE_GROUP + "|0", FACTION_GROUP + "|0");

            var gameId = UUID.randomUUID();
            host.delivery()
                    .accept(GameEventRecords.eraStarted(gameId, UUID.randomUUID(), 1, List.of(UUID.randomUUID())));
            host.delivery()
                    .accept(Map.of("topic", "other.events", "key", "k", "headers", Map.of(), "payload", new byte[0]));

            assertThat(watermarks.current())
                    .extracting(w -> w.groupId() + "|" + w.nextOffset())
                    .containsExactly(LIFECYCLE_GROUP + "|1", FACTION_GROUP + "|1");
        }
    }

    @Test
    @DisplayName("two instances in one JVM share no state and no sink")
    void twoInstances_areIsolated() {
        var otherPublished = new CopyOnWriteArrayList<Map<String, Object>>();
        try (var first = InMemoryTimelineHost.start(published::add).configureExecution();
                var second = InMemoryTimelineHost.start(otherPublished::add).configureExecution()) {
            var gameId = UUID.randomUUID();
            deliverEra(first, gameId);
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(eventTypes(gameId)).contains("EraResolutionCompleted"));

            assertThat(otherPublished).isEmpty();
            assertThat(second.bean(SourceWatermarks.class).current())
                    .allSatisfy(w -> assertThat(w.nextOffset()).isZero());
            assertThat(first.bean(SourceWatermarks.class).current())
                    .anySatisfy(w -> assertThat(w.nextOffset()).isPositive());
        }
    }

    private void deliverEra(InMemoryTimelineHost host, UUID gameId) {
        var delivery = host.delivery();
        delivery.accept(GameEventRecords.eraStarted(gameId, UUID.randomUUID(), 1, List.of(UUID.randomUUID())));
        delivery.accept(GameEventRecords.twoOutcomeEventDrawn(
                gameId, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 70, UUID.randomUUID(), 30));
        delivery.accept(GameEventRecords.resolutionStarted(gameId, resolutionEventId(gameId), 1));
    }

    private static UUID resolutionEventId(UUID gameId) {
        return new UUID(gameId.getMostSignificantBits(), 1);
    }

    private List<String> eventTypes(UUID gameId) {
        return published.stream()
                .filter(record -> gameId.toString().equals(record.get("key")))
                .map(record -> headers(record).get("eventType"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Map<String, Object> record) {
        return (Map<String, String>) record.get("headers");
    }
}
