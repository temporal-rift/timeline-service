package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/** Consumes {@code ResolutionStarted} from {@code game.events}: triggers resolution of the era's events. */
@Component
class ResolutionStartedKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResolutionStartedKafkaConsumer.class);
    private static final String BINDING_NAME = "Sessionpublish-resolution-started-out";
    private static final String CONSUMER = "futureevent.resolution-started";
    private static final int SUPPORTED_VERSION = 1;

    private final ProcessedEventPort processedEvents;
    private final ResolveEraUseCase resolveEra;
    private final ObjectMapper objectMapper;

    ResolutionStartedKafkaConsumer(
            ProcessedEventPort processedEvents, ResolveEraUseCase resolveEra, ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.resolveEra = resolveEra;
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
            log.warn("Malformed ResolutionStarted envelope (missing eventId) — discarding");
            return;
        }
        if (!isSupportedVersion(envelope.version())) {
            log.warn(
                    "Unsupported ResolutionStarted envelope version {} for event {} — skipping",
                    envelope.version(),
                    envelope.eventId());
            return;
        }
        if (!processedEvents.claim(envelope.eventId(), CONSUMER)) {
            log.debug("Duplicate ResolutionStarted event {} ignored", envelope.eventId());
            return;
        }

        var payload = GameEventPayloads.read(objectMapper, message.getPayload(), ResolutionStartedPayload.class);
        resolveEra.resolve(payload.gameId(), payload.eraNumber());
    }

    private static boolean isSupportedVersion(Integer version) {
        return version != null && version == SUPPORTED_VERSION;
    }
}
