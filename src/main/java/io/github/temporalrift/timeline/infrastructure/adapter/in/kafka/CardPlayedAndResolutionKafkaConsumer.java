package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.ApplyProbabilityShiftUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase.CardModifier;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code CardPlayed} and {@code ResolutionStarted} from {@code game.events} in one Kafka
 * consumer group (design.md Decision 1/3, revised after PR #25 review): a single
 * {@code @KafkaListener} reading one assigned partition processes records strictly in the order
 * {@code game-service} produced them, so every accepted card's effect for an era is durably applied
 * before that era's {@code ResolutionStarted} is handled. Splitting these into two independent
 * consumer groups (the original design) let a lagging card consumer be overtaken by the resolution
 * consumer — reachable in practice (consumer rebalance, GC pause, retry), not just theoretical —
 * silently losing the card's effect.
 */
@Component
class CardPlayedAndResolutionKafkaConsumer {

    private static final String GROUP_ID = "timeline-service.futureevent.card-played-and-resolution";
    private static final GameEventIngestion.Spec CARD_PLAYED_SPEC =
            new GameEventIngestion.Spec("CardPlayed", "futureevent.card-played", 1);
    private static final GameEventIngestion.Spec RESOLUTION_STARTED_SPEC =
            new GameEventIngestion.Spec("ResolutionStarted", "futureevent.resolution-started", 1);

    private final ProcessedEventPort processedEvents;
    private final ApplyProbabilityShiftUseCase applyProbabilityShift;
    private final PlayCardModifierUseCase playCardModifier;
    private final ResolveEraUseCase resolveEra;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            ApplyProbabilityShiftUseCase applyProbabilityShift,
            PlayCardModifierUseCase playCardModifier,
            ResolveEraUseCase resolveEra,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.applyProbabilityShift = applyProbabilityShift;
        this.playCardModifier = playCardModifier;
        this.resolveEra = resolveEra;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = GROUP_ID)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, CARD_PLAYED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            handleCardPlayed(payload);
        });
        GameEventIngestion.accept(message, RESOLUTION_STARTED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload =
                            GameEventPayloads.read(objectMapper, message.getPayload(), ResolutionStartedPayload.class);
                    resolveEra.resolve(payload.gameId(), payload.eraNumber());
                });
    }

    private void handleCardPlayed(CardPlayedPayload payload) {
        switch (payload.cardType()) {
            case "PUSH" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.targetEventId(),
                        new ProbabilityShift.Push(payload.targetOutcomeId()));
            case "SUPPRESS" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.targetEventId(),
                        new ProbabilityShift.Suppress(payload.targetOutcomeId()));
            case "SWING" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.targetEventId(),
                        new ProbabilityShift.Swing(payload.sourceOutcomeId(), payload.targetOutcomeId()));
            case "AMPLIFY" ->
                playCardModifier.play(
                        new CardModifier.Amplify(payload.gameId(), payload.eraNumber(), payload.roundNumber()));
            case "NULLIFY" ->
                playCardModifier.play(
                        new CardModifier.Nullify(payload.gameId(), payload.eraNumber(), payload.roundNumber()));
            case "REDIRECT" ->
                playCardModifier.play(new CardModifier.Redirect(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.targetEventId(),
                        payload.targetOutcomeId()));
            case "STALL" ->
                playCardModifier.play(new CardModifier.Stall(
                        payload.gameId(), payload.eraNumber(), payload.roundNumber(), payload.targetEventId()));
            case "INTERCEPT", "SCAN", "TRACE", "DECOY", "JAM" ->
                playCardModifier.play(
                        new CardModifier.NoOp(payload.gameId(), payload.eraNumber(), payload.roundNumber()));
            case null, default -> {
                // Unknown/future cardType: eventId already claimed by GameEventIngestion, no effect this slice.
            }
        }
    }
}
