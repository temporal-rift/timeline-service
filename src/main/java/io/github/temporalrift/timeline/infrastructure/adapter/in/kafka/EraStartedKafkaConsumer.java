package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(EraStartedKafkaConsumer.class);
    private static final String BINDING_NAME = "Sessionpublish-era-started-out";
    private static final String CONSUMER = "futureevent.era-started";
    private static final int SUPPORTED_VERSION = 1;

    private final ProcessedEventPort processedEvents;

    EraStartedKafkaConsumer(ProcessedEventPort processedEvents) {
        this.processedEvents = processedEvents;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    public void handle(Message<Object> message) {
        var envelope = GameEventEnvelope.from(message);
        if (!BINDING_NAME.equals(envelope.bindingName())) {
            return;
        }
        if (envelope.eventId() == null) {
            log.warn("Malformed EraStarted envelope (missing eventId) — discarding");
            return;
        }
        if (!isSupportedVersion(envelope.version())) {
            log.warn(
                    "Unsupported EraStarted envelope version {} for event {} — skipping",
                    envelope.version(),
                    envelope.eventId());
            return;
        }
        if (!processedEvents.claim(envelope.eventId(), CONSUMER)) {
            log.debug("Duplicate EraStarted event {} ignored", envelope.eventId());
        }
    }

    private static boolean isSupportedVersion(Integer version) {
        return version != null && version == SUPPORTED_VERSION;
    }
}
