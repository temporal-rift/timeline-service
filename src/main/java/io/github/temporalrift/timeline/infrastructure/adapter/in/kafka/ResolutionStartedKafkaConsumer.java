package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

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

    private static final String CONSUMER = "futureevent.resolution-started";
    private static final GameEventIngestion.Spec SPEC =
            new GameEventIngestion.Spec("Sessionpublish-resolution-started-out", "ResolutionStarted", CONSUMER, 1);

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
        GameEventIngestion.accept(message, SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), ResolutionStartedPayload.class);
            resolveEra.resolve(payload.gameId(), payload.eraNumber());
        });
    }
}
