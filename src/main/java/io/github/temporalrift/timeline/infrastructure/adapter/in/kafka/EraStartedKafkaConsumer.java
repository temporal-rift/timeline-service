package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code EraStarted} from {@code game.events}. This slice needs no state from it beyond
 * establishing that the era exists before {@code EventsDrawn} arrives (design.md Decision 5) — it is still
 * consumed, not skipped, so its {@code eventId} is claimed and unexpected redelivery/ordering is logged. The
 * payload is deserialized to enforce the current {@code carryOverEventIds} contract shape (rejecting the
 * retired {@code cascadedEventIds} shape) even though its values go unused this slice.
 */
@Component
class EraStartedKafkaConsumer {

    private static final String CONSUMER = "futureevent.era-started";
    private static final GameEventIngestion.Spec SPEC = new GameEventIngestion.Spec("EraStarted", CONSUMER, 1);

    private final ProcessedEventPort processedEvents;
    private final ObjectMapper objectMapper;

    EraStartedKafkaConsumer(ProcessedEventPort processedEvents, ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents)
                .ifPresent(envelope ->
                        GameEventPayloads.read(objectMapper, message.getPayload(), EraStartedPayload.class));
    }
}
