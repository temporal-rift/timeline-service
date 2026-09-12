package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChainSnapshot;

/**
 * Covers the snapshot-trigger wiring with in-memory ports. A live 20-append chain stream is unreachable by design —
 * chains complete at 3 links — so the trigger branch is verified at the {@code maybeSnapshot} method level rather
 * than through 20 domain appends.
 */
class JpaWeaverChainRepositorySnapshotTest {

    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemorySnapshots snapshots = new InMemorySnapshots();
    private final JpaWeaverChainRepository chains = new JpaWeaverChainRepository(
            eventStore, new EventStoreAppender(eventStore, objectMapper, clock), snapshots, objectMapper, clock);

    @Test
    void maybeSnapshot_offBoundary_savesNothing() {
        var chainId = seedChain();

        chains.maybeSnapshot(chainId, 19);

        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    void maybeSnapshot_onBoundary_persistsCurrentStateAtThatSequence() {
        var chainId = seedChain();

        chains.maybeSnapshot(chainId, 20);

        assertThat(snapshots.saved).containsKey(chainId);
        var saved = snapshots.saved.get(chainId);
        assertThat(saved.sequenceNr()).isEqualTo(20);
        assertThat(objectMapper.readValue(saved.snapshotData(), WeaverChainSnapshot.class))
                .isEqualTo(chains.findById(chainId).snapshot());
    }

    private UUID seedChain() {
        var chainId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        chains.append(chainId, new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()));
        chains.append(chainId, new ChainLinkAdded(chainId, eventId, UUID.randomUUID(), 1));
        return chainId;
    }

    static class InMemoryEventStore implements EventStorePort {
        private final Map<UUID, List<StoredEvent>> streams = new HashMap<>();

        @Override
        public void append(StoredEvent event) {
            streams.computeIfAbsent(event.aggregateId(), ignored -> new ArrayList<>())
                    .add(event);
        }

        @Override
        public List<StoredEvent> readStream(UUID aggregateId) {
            return List.copyOf(streams.getOrDefault(aggregateId, List.of()));
        }

        @Override
        public List<StoredEvent> readStreamFrom(UUID aggregateId, long fromSequenceNr) {
            return readStream(aggregateId).stream()
                    .filter(event -> event.sequenceNr() >= fromSequenceNr)
                    .toList();
        }

        @Override
        public long streamSize(UUID aggregateId) {
            return streams.getOrDefault(aggregateId, List.of()).size();
        }
    }

    static class InMemorySnapshots implements AggregateSnapshotPort {
        private final Map<UUID, AggregateSnapshot> saved = new HashMap<>();

        @Override
        public void save(AggregateSnapshot snapshot) {
            saved.put(snapshot.aggregateId(), snapshot);
        }

        @Override
        public Optional<AggregateSnapshot> findByAggregateId(UUID aggregateId) {
            return Optional.ofNullable(saved.get(aggregateId));
        }
    }
}
