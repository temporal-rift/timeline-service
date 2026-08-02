package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Optional;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code CardPlayed} from {@code game.events}: applies {@code PUSH}/{@code SUPPRESS}/{@code SWING}
 * to the target {@code FutureEvent} immediately (design.md Decision 1). Every other {@code cardType} still
 * has its {@code eventId} claimed but is otherwise a no-op this slice (deferred to a later one).
 */
@Component
class CardPlayedKafkaConsumer {

    private static final String CONSUMER = "futureevent.card-played";
    private static final GameEventIngestion.Spec SPEC =
            new GameEventIngestion.Spec("Actionpublish-card-played-out", "CardPlayed", CONSUMER, 1);

    private final ProcessedEventPort processedEvents;
    private final ApplyProbabilityShiftUseCase applyProbabilityShift;
    private final ObjectMapper objectMapper;

    CardPlayedKafkaConsumer(
            ProcessedEventPort processedEvents,
            ApplyProbabilityShiftUseCase applyProbabilityShift,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.applyProbabilityShift = applyProbabilityShift;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = "timeline-service." + CONSUMER)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            toProbabilityShift(payload).ifPresent(shift -> applyProbabilityShift.apply(payload.targetEventId(), shift));
        });
    }

    private static Optional<ProbabilityShift> toProbabilityShift(CardPlayedPayload payload) {
        return switch (payload.cardType()) {
            case "PUSH" -> Optional.of(new ProbabilityShift.Push(payload.targetOutcomeId()));
            case "SUPPRESS" -> Optional.of(new ProbabilityShift.Suppress(payload.targetOutcomeId()));
            case "SWING" ->
                Optional.of(new ProbabilityShift.Swing(payload.sourceOutcomeId(), payload.targetOutcomeId()));
            default -> Optional.empty();
        };
    }
}
