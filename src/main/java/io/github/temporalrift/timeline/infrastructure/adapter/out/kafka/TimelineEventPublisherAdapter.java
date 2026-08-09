package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Driven adapter that fulfils the {@link TimelineEventPublisher} port.
 *
 * <p>Maps the local payload to its generated wire type and publishes a {@link Message} directly. Unlike
 * game-service, nothing wires Spring Modulith to that call here — {@code OutboxEventListener}
 * (infrastructure.adapter.out.outbox) captures it instead (design.md Decision 4).
 */
@Component
class TimelineEventPublisherAdapter implements TimelineEventPublisher {

    private static final String SCS_DESTINATION_HEADER = "spring.cloud.stream.sendto.destination";
    private static final String TIMELINE_EVENTS_TOPIC = "timeline.events";

    private final ApplicationEventPublisher applicationEventPublisher;
    private final TimelineEventWireMapper mapper;

    TimelineEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher, TimelineEventWireMapper mapper) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.mapper = mapper;
    }

    @Override
    public void publish(TimelineEventEnvelope<?> event) {
        switch (event.payload()) {
            case ProbabilityStateCalculated e -> publish("ProbabilityStateCalculated", mapper.toWire(e), event);
            case OutcomeApplied e -> publish("OutcomeApplied", mapper.toWire(e), event);
            case ParadoxDetected e -> publish("ParadoxDetected", mapper.toWire(e), event);
            case EraResolutionCompleted e -> publish("EraResolutionCompleted", mapper.toWire(e), event);
            case ParadoxResolutionPhaseStarted e -> publish("ParadoxResolutionPhaseStarted", mapper.toWire(e), event);
            case ParadoxCascaded e -> publish("ParadoxCascaded", mapper.toWire(e), event);
            default ->
                throw new IllegalArgumentException(
                        "Unsupported timeline event payload: " + event.payload().getClass());
        }
    }

    private void publish(String eventType, Object payload, TimelineEventEnvelope<?> event) {
        Map<String, Object> headers = TimelineEventHeaders.populate(new LinkedHashMap<>(), event, eventType);
        headers.put(SCS_DESTINATION_HEADER, TIMELINE_EVENTS_TOPIC);

        Message<Object> message =
                MessageBuilder.withPayload(payload).copyHeaders(headers).build();
        applicationEventPublisher.publishEvent(message);
    }
}
