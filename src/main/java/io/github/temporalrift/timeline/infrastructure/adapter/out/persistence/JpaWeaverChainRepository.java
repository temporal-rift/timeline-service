package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.eventstore.AggregateSnapshot;
import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.AggregateSnapshotPort;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChainSnapshot;

@Repository
class JpaWeaverChainRepository implements WeaverChainRepository {

    private static final String AGGREGATE_TYPE = "WeaverChain";
    private static final int EVENT_VERSION = 1;
    static final int SNAPSHOT_INTERVAL = 20;

    private final EventStorePort eventStore;
    private final EventStoreAppender appender;
    private final AggregateSnapshotPort snapshots;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JpaWeaverChainRepository(
            EventStorePort eventStore,
            EventStoreAppender appender,
            AggregateSnapshotPort snapshots,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventStore = eventStore;
        this.appender = appender;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public WeaverChain findById(UUID chainId) {
        var snapshot = snapshots.findByAggregateId(chainId);
        if (snapshot.isPresent() && snapshot.get().sequenceNr() <= eventStore.streamSize(chainId)) {
            var tail = eventStore.readStreamFrom(chainId, snapshot.get().sequenceNr()).stream()
                    .map(this::toDomainEvent)
                    .toList();
            var state = objectMapper.readValue(snapshot.get().snapshotData(), WeaverChainSnapshot.class);
            return WeaverChain.restore(state, tail);
        }
        var history =
                eventStore.readStream(chainId).stream().map(this::toDomainEvent).toList();
        return WeaverChain.replay(chainId, history);
    }

    @Override
    @Transactional
    public void append(UUID chainId, WeaverChainEvent domainEvent) {
        appendBatch(chainId, List.of(domainEvent));
    }

    @Override
    @Transactional
    public void appendAll(UUID chainId, List<? extends WeaverChainEvent> domainEvents) {
        appendBatch(chainId, domainEvents);
    }

    private void appendBatch(UUID chainId, List<? extends WeaverChainEvent> domainEvents) {
        for (var domainEvent : domainEvents) {
            Objects.requireNonNull(domainEvent, "domainEvent");
        }
        var streamSize = eventStore.streamSize(chainId);
        for (var domainEvent : domainEvents) {
            streamSize = appender.append(chainId, AGGREGATE_TYPE, EVENT_VERSION, domainEvent);
        }
        maybeSnapshot(chainId, streamSize);
    }

    void maybeSnapshot(UUID chainId, long streamSize) {
        if (isSnapshotDue(streamSize)) {
            var chain = findById(chainId);
            snapshots.save(new AggregateSnapshot(
                    chainId,
                    AGGREGATE_TYPE,
                    objectMapper.writeValueAsString(chain.snapshot()),
                    streamSize,
                    clock.instant()));
        }
    }

    static boolean isSnapshotDue(long streamSize) {
        return streamSize > 0 && streamSize % SNAPSHOT_INTERVAL == 0;
    }

    private WeaverChainEvent toDomainEvent(StoredEvent stored) {
        return switch (stored.eventType()) {
            case "WeaverChainStarted" -> objectMapper.readValue(stored.payload(), WeaverChainStarted.class);
            case "ChainLinkAdded" -> objectMapper.readValue(stored.payload(), ChainLinkAdded.class);
            case "ChainCompleted" -> objectMapper.readValue(stored.payload(), ChainCompleted.class);
            case "ChainBroken" -> objectMapper.readValue(stored.payload(), ChainBroken.class);
            default -> throw new IllegalStateException("Unknown WeaverChain event type: " + stored.eventType());
        };
    }
}
