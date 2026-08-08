package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.EventStalled;
import io.github.temporalrift.timeline.domain.event.EventUnstalled;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;
import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.port.out.EventStorePort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;

@Repository
class JpaFutureEventRepository implements FutureEventRepository {

    private static final String AGGREGATE_TYPE = "FutureEvent";
    private static final int EVENT_VERSION = 1;

    private final EventStorePort eventStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JpaFutureEventRepository(EventStorePort eventStore, ObjectMapper objectMapper, Clock clock) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public FutureEvent findById(UUID eventId) {
        var history =
                eventStore.readStream(eventId).stream().map(this::toDomainEvent).toList();
        return FutureEvent.replay(eventId, history);
    }

    @Override
    public void append(UUID eventId, Object domainEvent) {
        var sequenceNr = eventStore.readStream(eventId).size();
        var eventType = domainEvent.getClass().getSimpleName();
        var payload = objectMapper.writeValueAsString(domainEvent);
        eventStore.append(new StoredEvent(
                UUID.randomUUID(),
                eventId,
                AGGREGATE_TYPE,
                eventType,
                EVENT_VERSION,
                payload,
                clock.instant(),
                sequenceNr));
    }

    private Object toDomainEvent(StoredEvent stored) {
        return switch (stored.eventType()) {
            case "FutureEventDrafted" -> objectMapper.readValue(stored.payload(), FutureEventDrafted.class);
            case "OutcomeApplied" -> objectMapper.readValue(stored.payload(), OutcomeApplied.class);
            case "ProbabilityShifted" -> objectMapper.readValue(stored.payload(), ProbabilityShifted.class);
            case "EventStalled" -> objectMapper.readValue(stored.payload(), EventStalled.class);
            case "EventUnstalled" -> objectMapper.readValue(stored.payload(), EventUnstalled.class);
            default -> throw new IllegalStateException("Unknown FutureEvent event type: " + stored.eventType());
        };
    }
}
