package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.AdjustedBandsPublished;
import io.github.temporalrift.timeline.domain.event.CascadeCarriedForwardEvent;
import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionArmedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionConsumedEvent;
import io.github.temporalrift.timeline.domain.event.ChainReAnchoredEvent;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateRevealed;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.ResolutionWarning;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Driven adapter that fulfils the {@link TimelineEventPublisher} port.
 *
 * <p>Maps the local payload to its generated wire type and publishes a {@link Message} directly. Unlike
 * game-service, nothing wires Spring Modulith to that call here — {@code OutboxEventListener}
 * (infrastructure.adapter.out.outbox) captures it instead (the governing design Decision 4).
 */
@Component
class TimelineEventPublisherAdapter implements TimelineEventPublisher {

    private static final String SCS_DESTINATION_HEADER = "spring.cloud.stream.sendto.destination";
    private static final String TIMELINE_EVENTS_TOPIC = "timeline.events";

    private final ApplicationEventPublisher applicationEventPublisher;
    private final TimelineEventWireMapper mapper;
    private final Validator validator;

    TimelineEventPublisherAdapter(
            ApplicationEventPublisher applicationEventPublisher, TimelineEventWireMapper mapper, Validator validator) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Override
    public void publish(TimelineEventEnvelope<?> event) {
        switch (event.payload()) {
            case ProbabilityStateCalculated e -> publish("ProbabilityStateCalculated", mapper.toWire(e), event);
            case ProbabilityStateRevealed e -> publish("ProbabilityStateRevealed", mapper.toWire(e), event);
            case OutcomeApplied e -> publish("OutcomeApplied", mapper.toWire(e), event);
            case ParadoxDetected e -> publish("ParadoxDetected", mapper.toWire(e), event);
            case EraResolutionCompleted e -> publish("EraResolutionCompleted", mapper.toWire(e), event);
            case ParadoxResolutionPhaseStarted e -> publish("ParadoxResolutionPhaseStarted", mapper.toWire(e), event);
            case ParadoxCascaded e -> publish("ParadoxCascaded", mapper.toWire(e), event);
            case ParadoxResolved e -> publish("ParadoxResolved", mapper.toWire(e), event);
            case AdjustedBandsPublished e -> publish("AdjustedBandsPublished", mapper.toWire(e), event);
            case CorruptInversionConfirmed e -> publish("CorruptInversionConfirmed", mapper.toWire(e), event);
            case ChainLinkAddedEvent e -> publish("ChainLinkAdded", mapper.toWire(e), event);
            case ChainCompletedEvent e -> publish("ChainCompleted", mapper.toWire(e), event);
            case ChainBrokenEvent e -> publish("ChainBroken", mapper.toWire(e), event);
            case ChainLinkInvalidatedEvent e -> publish("ChainLinkInvalidated", mapper.toWire(e), event);
            case ThreadRejectedEvent e -> publish("ThreadRejected", mapper.toWire(e), event);
            case SpecialRejectedEvent e -> publish("SpecialRejected", mapper.toWire(e), event);
            case ChainProtectionArmedEvent e -> publish("ChainProtectionArmed", mapper.toWire(e), event);
            case ChainProtectionConsumedEvent e -> publish("ChainProtectionConsumed", mapper.toWire(e), event);
            case ChainReAnchoredEvent e -> publish("ChainReAnchored", mapper.toWire(e), event);
            case CascadeCarriedForwardEvent e -> publish("CascadeCarriedForward", mapper.toWire(e), event);
            case ResolutionFailed e -> publish("ResolutionFailed", mapper.toWire(e), event);
            case ResolutionWarning e -> publish("ResolutionWarning", mapper.toWire(e), event);
            default ->
                throw new IllegalArgumentException(
                        "Unsupported timeline event payload: " + event.payload().getClass());
        }
    }

    private void publish(String eventType, Object payload, TimelineEventEnvelope<?> event) {
        validatePayload(eventType, payload);
        Map<String, Object> headers = TimelineEventHeaders.populate(new LinkedHashMap<>(), event, eventType);
        headers.put(SCS_DESTINATION_HEADER, TIMELINE_EVENTS_TOPIC);

        Message<Object> message =
                MessageBuilder.withPayload(payload).copyHeaders(headers).build();
        applicationEventPublisher.publishEvent(message);
    }

    private void validatePayload(String eventType, Object payload) {
        var violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            var details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new ConstraintViolationException(
                    "Invalid timeline event payload '" + eventType + "': " + details, violations);
        }
    }
}
