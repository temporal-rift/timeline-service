package io.github.temporalrift.timeline.domain.port.out;

/** Driven port for publishing a resolved fact onto {@code timeline.events}. */
public interface TimelineEventPublisher {

    void publish(TimelineEventEnvelope<?> event);
}
