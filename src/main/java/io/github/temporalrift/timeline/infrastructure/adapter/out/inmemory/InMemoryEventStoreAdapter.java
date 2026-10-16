package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

@Component
@Profile("in-memory")
class InMemoryEventStoreAdapter implements EventStorePort {

    private final Table<StreamPosition, StoredEvent> events;

    InMemoryEventStoreAdapter(InMemoryDatabase database) {
        this.events = database.table(TableNames.EVENT_STORE);
    }

    /** The unique {@code (aggregateId, sequenceNr)} constraint: an occupied position fails the append. */
    @Override
    public void append(StoredEvent event) {
        events.insert(new StreamPosition(event.aggregateId(), event.sequenceNr()), event);
    }

    @Override
    public List<StoredEvent> readStream(UUID aggregateId) {
        return readStreamFrom(aggregateId, 0);
    }

    @Override
    public List<StoredEvent> readStreamFrom(UUID aggregateId, long fromSequenceNr) {
        return events
                .list(event -> event.aggregateId().equals(aggregateId) && event.sequenceNr() >= fromSequenceNr)
                .stream()
                .sorted(Comparator.comparingLong(StoredEvent::sequenceNr))
                .toList();
    }

    @Override
    public long streamSize(UUID aggregateId) {
        return events.list(event -> event.aggregateId().equals(aggregateId)).size();
    }

    private record StreamPosition(UUID aggregateId, long sequenceNr) {}
}
