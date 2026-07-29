package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.eventstore.StoredEvent;

/**
 * Driven port for the append-only event store. Event-sourced aggregates are rebuilt by replaying their stream rather
 * than loading a current-state row.
 */
public interface EventStorePort {

    /**
     * Appends one event to its aggregate's stream. Implementations enforce the unique {@code (aggregateId, sequenceNr)}
     * constraint, so a concurrent append at the same position fails rather than silently overwriting.
     */
    void append(StoredEvent event);

    /** Reads an aggregate's full event stream in ascending {@code sequenceNr} order. */
    List<StoredEvent> readStream(UUID aggregateId);
}
