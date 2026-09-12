package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;

/**
 * Shared sequence-number assignment and JSON serialization for event-store appends, so aggregate repositories do not
 * duplicate the read-size-then-append shape and cannot drift apart.
 */
@Component
class EventStoreAppender {

    private final EventStorePort eventStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    EventStoreAppender(EventStorePort eventStore, ObjectMapper objectMapper, Clock clock) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Appends one event to its aggregate's stream at the next sequence number.
     *
     * @return the stream size after the append, for snapshot-boundary checks
     */
    long append(UUID aggregateId, String aggregateType, int eventVersion, Object domainEvent) {
        var sequenceNr = eventStore.streamSize(aggregateId);
        eventStore.append(new StoredEvent(
                UUID.randomUUID(),
                aggregateId,
                aggregateType,
                domainEvent.getClass().getSimpleName(),
                eventVersion,
                objectMapper.writeValueAsString(domainEvent),
                clock.instant(),
                sequenceNr));
        return sequenceNr + 1;
    }
}
