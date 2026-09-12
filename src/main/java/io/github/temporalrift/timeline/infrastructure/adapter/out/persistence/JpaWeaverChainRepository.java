package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
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
    private final AggregateSnapshotPort snapshots;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JpaWeaverChainRepository(
            EventStorePort eventStore, AggregateSnapshotPort snapshots, ObjectMapper objectMapper, Clock clock) {
        this.eventStore = eventStore;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public WeaverChain findById(UUID chainId) {
        var history =
                eventStore.readStream(chainId).stream().map(this::toDomainEvent).toList();
        var snapshot = snapshots.findByAggregateId(chainId);
        if (snapshot.isPresent() && snapshot.get().sequenceNr() <= history.size()) {
            var state = objectMapper.readValue(snapshot.get().snapshotData(), WeaverChainSnapshot.class);
            var tail = history.subList((int) snapshot.get().sequenceNr(), history.size());
            return WeaverChain.restore(state, tail);
        }
        return WeaverChain.replay(chainId, history);
    }

    @Override
    public void append(UUID chainId, Object domainEvent) {
        var sequenceNr = eventStore.readStream(chainId).size();
        var eventType = domainEvent.getClass().getSimpleName();
        var payload = objectMapper.writeValueAsString(domainEvent);
        eventStore.append(new StoredEvent(
                UUID.randomUUID(),
                chainId,
                AGGREGATE_TYPE,
                eventType,
                EVENT_VERSION,
                payload,
                clock.instant(),
                sequenceNr));
        if (isSnapshotDue(sequenceNr + 1)) {
            var chain = findById(chainId);
            snapshots.save(new AggregateSnapshot(
                    chainId,
                    AGGREGATE_TYPE,
                    objectMapper.writeValueAsString(chain.snapshot()),
                    sequenceNr + 1,
                    clock.instant()));
        }
    }

    static boolean isSnapshotDue(long streamSize) {
        return streamSize > 0 && streamSize % SNAPSHOT_INTERVAL == 0;
    }

    private Object toDomainEvent(StoredEvent stored) {
        return switch (stored.eventType()) {
            case "WeaverChainStarted" -> objectMapper.readValue(stored.payload(), WeaverChainStarted.class);
            case "ChainLinkAdded" -> objectMapper.readValue(stored.payload(), ChainLinkAdded.class);
            case "ChainCompleted" -> objectMapper.readValue(stored.payload(), ChainCompleted.class);
            case "ChainBroken" -> objectMapper.readValue(stored.payload(), ChainBroken.class);
            default -> throw new IllegalStateException("Unknown WeaverChain event type: " + stored.eventType());
        };
    }
}
