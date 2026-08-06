package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code EraStarted} from {@code game.events}. This slice needs no state from it beyond
 * establishing that the era exists before {@code EventsDrawn} arrives (design.md Decision 5) — it is still
 * consumed, not skipped, so its {@code eventId} is claimed and unexpected redelivery/ordering is logged.
 */
@Component
class EraStartedKafkaConsumer {

    private static final String CONSUMER = "futureevent.era-started";
    private static final GameEventIngestion.Spec SPEC = new GameEventIngestion.Spec("EraStarted", CONSUMER, 1);

    private final ProcessedEventPort processedEvents;

    EraStartedKafkaConsumer(ProcessedEventPort processedEvents) {
        this.processedEvents = processedEvents;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents);
    }
}
