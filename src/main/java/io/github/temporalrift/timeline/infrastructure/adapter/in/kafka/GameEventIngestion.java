package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Shared envelope-validation prologue for the {@code game.events} consumers: filter by the stable
 * {@code eventType} header, discard a missing {@code eventId}, skip an unsupported version, then claim the
 * {@code eventId}.
 * Extracted after SonarCloud flagged the duplication across the three consumers, which each repeated
 * these same four checks with only the event name and consumer name differing.
 */
final class GameEventIngestion {

    private static final Logger log = LoggerFactory.getLogger(GameEventIngestion.class);

    private GameEventIngestion() {}

    /**
     * @return the envelope if the message matches {@code spec}'s event type, is well-formed, has a
     *     supported version, and was newly claimed for {@code spec}'s consumer; empty if the caller
     *     should do nothing further (the reason, if any, is already logged here).
     */
    static Optional<GameEventEnvelope> accept(Message<Object> message, Spec spec, ProcessedEventPort processedEvents) {
        var envelope = GameEventEnvelope.from(message);
        if (!matches(envelope, spec)) {
            return Optional.empty();
        }
        if (envelope.eventId() == null) {
            log.warn("Malformed {} envelope (missing eventId) — discarding", spec.eventName());
            return Optional.empty();
        }
        if (envelope.version() == null || envelope.version() != spec.supportedVersion()) {
            log.warn(
                    "Unsupported {} envelope version {} for event {} — skipping",
                    spec.eventName(),
                    envelope.version(),
                    envelope.eventId());
            return Optional.empty();
        }
        if (!processedEvents.claim(envelope.eventId(), spec.consumer())) {
            log.debug("Duplicate {} event {} ignored", spec.eventName(), envelope.eventId());
            return Optional.empty();
        }
        return Optional.of(envelope);
    }

    private static boolean matches(GameEventEnvelope envelope, Spec spec) {
        return spec.eventName().equals(envelope.eventType());
    }

    record Spec(String eventName, String consumer, int supportedVersion) {}
}
