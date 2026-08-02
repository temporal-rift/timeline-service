package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Optional;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code CardPlayed} and {@code ResolutionStarted} from {@code game.events} in one Kafka
 * consumer group (design.md Decision 1/3, revised after PR #25 review): a single
 * {@code @KafkaListener} reading one assigned partition processes records strictly in the order
 * {@code game-service} produced them, so every accepted shifter card for an era is durably applied
 * before that era's {@code ResolutionStarted} is handled. Splitting these into two independent
 * consumer groups (the original design) let a lagging card consumer be overtaken by the resolution
 * consumer — reachable in practice (consumer rebalance, GC pause, retry), not just theoretical —
 * silently losing the card's effect. {@code PUSH}/{@code SUPPRESS}/{@code SWING} apply immediately;
 * every other {@code cardType} still has its {@code eventId} claimed but is otherwise a no-op this
 * slice (deferred to a later one).
 */
@Component
class CardPlayedAndResolutionKafkaConsumer {

    private static final String GROUP_ID = "timeline-service.futureevent.card-played-and-resolution";
    private static final GameEventIngestion.Spec CARD_PLAYED_SPEC =
            new GameEventIngestion.Spec("Actionpublish-card-played-out", "CardPlayed", "futureevent.card-played", 1);
    private static final GameEventIngestion.Spec RESOLUTION_STARTED_SPEC = new GameEventIngestion.Spec(
            "Sessionpublish-resolution-started-out", "ResolutionStarted", "futureevent.resolution-started", 1);

    private final ProcessedEventPort processedEvents;
    private final ApplyProbabilityShiftUseCase applyProbabilityShift;
    private final ResolveEraUseCase resolveEra;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            ApplyProbabilityShiftUseCase applyProbabilityShift,
            ResolveEraUseCase resolveEra,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.applyProbabilityShift = applyProbabilityShift;
        this.resolveEra = resolveEra;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = GROUP_ID)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, CARD_PLAYED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            toProbabilityShift(payload).ifPresent(shift -> applyProbabilityShift.apply(payload.targetEventId(), shift));
        });
        GameEventIngestion.accept(message, RESOLUTION_STARTED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload =
                            GameEventPayloads.read(objectMapper, message.getPayload(), ResolutionStartedPayload.class);
                    resolveEra.resolve(payload.gameId(), payload.eraNumber());
                });
    }

    private static Optional<ProbabilityShift> toProbabilityShift(CardPlayedPayload payload) {
        return switch (payload.cardType()) {
            case "PUSH" -> Optional.of(new ProbabilityShift.Push(payload.targetOutcomeId()));
            case "SUPPRESS" -> Optional.of(new ProbabilityShift.Suppress(payload.targetOutcomeId()));
            case "SWING" ->
                Optional.of(new ProbabilityShift.Swing(payload.sourceOutcomeId(), payload.targetOutcomeId()));
            case null, default -> Optional.empty();
        };
    }
}
