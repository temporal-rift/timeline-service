package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/** Consumes {@code EventsDrawn} from {@code game.events}: drafts one {@code FutureEvent} per drawn event. */
@Component
class EventsDrawnKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventsDrawnKafkaConsumer.class);
    private static final String BINDING_NAME = "Sessionpublish-events-drawn-out";
    private static final String CONSUMER = "futureevent.events-drawn";
    private static final int SUPPORTED_VERSION = 1;

    private final ProcessedEventPort processedEvents;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final ObjectMapper objectMapper;

    EventsDrawnKafkaConsumer(
            ProcessedEventPort processedEvents,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        var envelope = GameEventEnvelope.from(message);
        if (!BINDING_NAME.equals(envelope.bindingName())) {
            return;
        }
        if (envelope.eventId() == null) {
            log.warn("Malformed EventsDrawn envelope (missing eventId) — discarding");
            return;
        }
        if (!isSupportedVersion(envelope.version())) {
            log.warn(
                    "Unsupported EventsDrawn envelope version {} for event {} — skipping",
                    envelope.version(),
                    envelope.eventId());
            return;
        }
        if (!processedEvents.claim(envelope.eventId(), CONSUMER)) {
            log.debug("Duplicate EventsDrawn event {} ignored", envelope.eventId());
            return;
        }

        var payload = GameEventPayloads.read(objectMapper, message.getPayload(), EventsDrawnPayload.class);
        for (var futureEvent : payload.events()) {
            var outcomes = futureEvent.outcomes().stream()
                    .map(o -> new Outcome(o.outcomeId(), o.description(), o.initialProbability()))
                    .toList();
            futureEvents.append(futureEvent.eventId(), new FutureEventDrafted(futureEvent.eventId(), outcomes));
            eraIndex.record(futureEvent.eventId(), payload.gameId(), payload.eraNumber());
        }
    }

    private static boolean isSupportedVersion(Integer version) {
        return version != null && version == SUPPORTED_VERSION;
    }
}
