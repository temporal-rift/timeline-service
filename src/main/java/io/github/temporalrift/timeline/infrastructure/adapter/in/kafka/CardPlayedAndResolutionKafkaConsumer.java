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
import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase;
import io.github.temporalrift.timeline.application.port.in.PlaySpecialActionUseCase.SpecialAction;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolvePendingCorruptUseCase;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Consumes {@code CardPlayed}, {@code SpecialActionPlayed}, {@code ActionRoundClosed}, {@code ResolutionStarted},
 * and {@code ParadoxResolutionCardPlayed} from {@code game.events} in one Kafka consumer group (design.md Decision
 * 1/3 of timeline-mvp4-card-modifiers, revised after PR #25 review; extended by timeline-mvp5-faction-specials
 * Decision 2, timeline-mvp8-paradox-completion): a single {@code @KafkaListener} reading one assigned partition
 * processes records strictly in the order {@code game-service} produced them, so a round's {@code CardPlayed}/
 * {@code SpecialActionPlayed} effects are durably applied before that round's {@code ActionRoundClosed} resolves
 * any pending {@code CORRUPT}, every era's card/special effects are applied before that era's
 * {@code ResolutionStarted} is handled, and a resolution-phase submission is applied in the order it was played.
 * Splitting these into independent consumer groups would let a lagging one be overtaken by a faster one —
 * reachable in practice (consumer rebalance, GC pause, retry), not just theoretical — silently losing or
 * misordering an effect. {@code ParadoxResolutionCardPlayed} has no published {@code game.events} contract yet
 * (temporal-rift/game-service#110, non-blocking) — its payload is hand-rolled against the documented shape, same
 * as every other message this consumer already handles.
 */
@Component
class CardPlayedAndResolutionKafkaConsumer {

    private static final String GROUP_ID = "timeline-service.futureevent.card-played-and-resolution";
    private static final GameEventIngestion.Spec CARD_PLAYED_SPEC =
            new GameEventIngestion.Spec("CardPlayed", "futureevent.card-played", 1);
    private static final GameEventIngestion.Spec SPECIAL_ACTION_PLAYED_SPEC =
            new GameEventIngestion.Spec("SpecialActionPlayed", "futureevent.special-action-played", 1);
    private static final GameEventIngestion.Spec ACTION_ROUND_CLOSED_SPEC =
            new GameEventIngestion.Spec("ActionRoundClosed", "futureevent.action-round-closed", 1);
    private static final GameEventIngestion.Spec RESOLUTION_STARTED_SPEC =
            new GameEventIngestion.Spec("ResolutionStarted", "futureevent.resolution-started", 1);
    private static final GameEventIngestion.Spec PARADOX_RESOLUTION_CARD_PLAYED_SPEC =
            new GameEventIngestion.Spec("ParadoxResolutionCardPlayed", "futureevent.paradox-resolution-card-played", 1);

    private final ProcessedEventPort processedEvents;
    private final ApplyProbabilityShiftUseCase applyProbabilityShift;
    private final PlayCardModifierUseCase playCardModifier;
    private final PlaySpecialActionUseCase playSpecialAction;
    private final ResolvePendingCorruptUseCase resolvePendingCorrupt;
    private final ResolveEraUseCase resolveEra;
    private final PlayParadoxResolutionCardUseCase playParadoxResolutionCard;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            ApplyProbabilityShiftUseCase applyProbabilityShift,
            PlayCardModifierUseCase playCardModifier,
            PlaySpecialActionUseCase playSpecialAction,
            ResolvePendingCorruptUseCase resolvePendingCorrupt,
            ResolveEraUseCase resolveEra,
            PlayParadoxResolutionCardUseCase playParadoxResolutionCard,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.applyProbabilityShift = applyProbabilityShift;
        this.playCardModifier = playCardModifier;
        this.playSpecialAction = playSpecialAction;
        this.resolvePendingCorrupt = resolvePendingCorrupt;
        this.resolveEra = resolveEra;
        this.playParadoxResolutionCard = playParadoxResolutionCard;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = GROUP_ID)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, CARD_PLAYED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            handleCardPlayed(payload);
        });
        GameEventIngestion.accept(message, SPECIAL_ACTION_PLAYED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), SpecialActionPlayedPayload.class);
                    playSpecialAction.play(toSpecialAction(payload));
                });
        GameEventIngestion.accept(message, ACTION_ROUND_CLOSED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload =
                            GameEventPayloads.read(objectMapper, message.getPayload(), ActionRoundClosedPayload.class);
                    resolvePendingCorrupt.resolve(payload.gameId(), payload.eraNumber(), payload.roundNumber());
                });
        GameEventIngestion.accept(message, RESOLUTION_STARTED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload =
                            GameEventPayloads.read(objectMapper, message.getPayload(), ResolutionStartedPayload.class);
                    resolveEra.resolve(payload.gameId(), payload.eraNumber());
                });
        GameEventIngestion.accept(message, PARADOX_RESOLUTION_CARD_PLAYED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), ParadoxResolutionCardPlayedPayload.class);
                    playParadoxResolutionCard.play(
                            payload.gameId(),
                            payload.eraNumber(),
                            payload.playerId(),
                            payload.cardType(),
                            payload.targetEventId(),
                            payload.targetOutcomeId());
                });
    }

    private void handleCardPlayed(CardPlayedPayload payload) {
        switch (payload.cardType()) {
            case "PUSH" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.playerId(),
                        payload.targetEventId(),
                        new ProbabilityShift.Push(payload.targetOutcomeId()));
            case "SUPPRESS" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.playerId(),
                        payload.targetEventId(),
                        new ProbabilityShift.Suppress(payload.targetOutcomeId()));
            case "SWING" ->
                applyProbabilityShift.apply(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        payload.playerId(),
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

    private static SpecialAction toSpecialAction(SpecialActionPlayedPayload payload) {
        return switch (payload.specialAction()) {
            case "SEAL" -> new SpecialAction.Seal(payload.targetEventId(), payload.targetOutcomeId());
            case "ANNIHILATE" -> new SpecialAction.Annihilate(payload.targetEventId(), payload.targetOutcomeId());
            case "CORRUPT" ->
                new SpecialAction.Corrupt(
                        payload.gameId(), payload.eraNumber(), payload.roundNumber(), payload.targetPlayerId());
            case null, default -> new SpecialAction.NoOp();
        };
    }
}
