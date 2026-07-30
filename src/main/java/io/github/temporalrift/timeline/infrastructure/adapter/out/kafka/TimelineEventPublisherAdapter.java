package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.producer.DefaultServiceEventsProducer;

/**
 * Driven adapter that fulfils the {@link TimelineEventPublisher} port.
 *
 * <p>Maps the local payload to its generated wire type and publishes through the ZenWave-generated
 * {@link DefaultServiceEventsProducer}, which calls {@code applicationEventPublisher.publishEvent(message)}
 * internally. Unlike game-service, nothing wires Spring Modulith to that call here — {@code
 * OutboxEventListener} (infrastructure.adapter.out.outbox) captures it instead (design.md Decision 4).
 */
@Component
class TimelineEventPublisherAdapter implements TimelineEventPublisher {

    private final DefaultServiceEventsProducer producer;
    private final TimelineEventWireMapper mapper;

    TimelineEventPublisherAdapter(DefaultServiceEventsProducer producer, TimelineEventWireMapper mapper) {
        this.producer = producer;
        this.mapper = mapper;
    }

    @Override
    public void publish(TimelineEventEnvelope<?> event) {
        switch (event.payload()) {
            case ProbabilityStateCalculated e ->
                producer.publishProbabilityStateCalculated(
                        mapper.toWire(e),
                        TimelineEventHeaders.populate(
                                new DefaultServiceEventsProducer.ProbabilityStateCalculatedPayloadHeaders(), event));
            case OutcomeApplied e ->
                producer.publishOutcomeApplied(
                        mapper.toWire(e),
                        TimelineEventHeaders.populate(
                                new DefaultServiceEventsProducer.OutcomeAppliedPayloadHeaders(), event));
            default ->
                throw new IllegalArgumentException(
                        "Unsupported timeline event payload: " + event.payload().getClass());
        }
    }
}
