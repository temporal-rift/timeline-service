package io.github.temporalrift.timeline.domain.port.out;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;

/** Driven port for loading/appending to a {@link FutureEvent}'s event-sourced stream. */
public interface FutureEventRepository {

    /** @throws io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException if no stream exists */
    FutureEvent findById(UUID eventId);

    /** Appends one new domain event to {@code eventId}'s stream. */
    void append(UUID eventId, Object domainEvent);
}
