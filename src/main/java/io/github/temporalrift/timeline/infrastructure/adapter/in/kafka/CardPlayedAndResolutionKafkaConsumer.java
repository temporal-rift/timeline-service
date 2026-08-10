package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Set;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.ApplyMomentumBonusUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

/**
 * Consumes {@code CardPlayed}, {@code SpecialActionPlayed}, {@code ActionRoundClosed}, {@code ResolutionStarted},
 * {@code ParadoxResolutionCardPlayed}, and {@code ActivistDeclarationRecorded} from {@code game.events} in one
 * Kafka consumer group (design.md Decision 1/3 of timeline-mvp4-card-modifiers, revised after PR #25 review;
 * extended by timeline-mvp5-faction-specials Decision 2, timeline-mvp8-paradox-completion, superseded for
 * {@code CardPlayed}/{@code SpecialActionPlayed} by timeline-mvp9-resolution-ordering-paradox-cards design.md
 * Decision 1, and extended again by add-remaining-faction-specials design.md "ActivistDeclarationRecorded is
 * consumed by the existing CardPlayedAndResolutionKafkaConsumer, not a new class"): a single {@code @KafkaListener}
 * reading one assigned partition processes records strictly in the order {@code game-service} produced them, so
 * a round's buffered actions (including a Rally declaration) are durably recorded before that round's
 * {@code ActionRoundClosed} replays them in priority-tier order, every era's replayed effects are applied before
 * that era's {@code ResolutionStarted} is handled, and a resolution-phase submission is applied in the order it was
 * played. Splitting these into independent consumer groups would let a lagging one be overtaken by a faster one —
 * reachable in practice (consumer rebalance, GC pause, retry), not just theoretical — silently losing or
 * misordering an effect. {@code ParadoxResolutionCardPlayed} has no published {@code game.events} contract yet
 * (temporal-rift/apis#24, non-blocking) — its payload is hand-rolled against the documented shape, same as every
 * other message this consumer already handles.
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
    private static final GameEventIngestion.Spec ACTIVIST_DECLARATION_RECORDED_SPEC =
            new GameEventIngestion.Spec("ActivistDeclarationRecorded", "futureevent.activist-declaration-recorded", 1);

    private static final String DECLARATION_MODE_RALLY = "RALLY";
    private static final String DECLARATION_MODE_MOMENTUM = "MOMENTUM";

    /** Every {@code cardType} this consumer buffers for replay; an unrecognized type is claimed and dropped. */
    private static final Set<String> KNOWN_CARD_TYPES = Set.of(
            "PUSH",
            "SUPPRESS",
            "SWING",
            "COLLIDE",
            "AMPLIFY",
            "NULLIFY",
            "REDIRECT",
            "STALL",
            "INTERCEPT",
            "SCAN",
            "TRACE",
            "DECOY",
            "JAM");

    private static final Set<String> KNOWN_SPECIAL_ACTIONS = Set.of("SEAL", "ANNIHILATE", "CORRUPT", "MIMIC");

    private final ProcessedEventPort processedEvents;
    private final RoundActionBufferPort buffer;
    private final ReplayRoundActionsUseCase replayRoundActions;
    private final ResolveEraUseCase resolveEra;
    private final PlayParadoxResolutionCardUseCase playParadoxResolutionCard;
    private final ApplyMomentumBonusUseCase applyMomentumBonus;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            RoundActionBufferPort buffer,
            ReplayRoundActionsUseCase replayRoundActions,
            ResolveEraUseCase resolveEra,
            PlayParadoxResolutionCardUseCase playParadoxResolutionCard,
            ApplyMomentumBonusUseCase applyMomentumBonus,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.buffer = buffer;
        this.replayRoundActions = replayRoundActions;
        this.resolveEra = resolveEra;
        this.playParadoxResolutionCard = playParadoxResolutionCard;
        this.applyMomentumBonus = applyMomentumBonus;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = GROUP_ID)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, CARD_PLAYED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            if (payload.cardType() != null && KNOWN_CARD_TYPES.contains(payload.cardType())) {
                buffer.save(
                        payload.gameId(),
                        payload.eraNumber(),
                        payload.roundNumber(),
                        toBufferedAction(payload, envelope));
            }
        });
        GameEventIngestion.accept(message, SPECIAL_ACTION_PLAYED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), SpecialActionPlayedPayload.class);
                    if (payload.specialAction() != null && KNOWN_SPECIAL_ACTIONS.contains(payload.specialAction())) {
                        buffer.save(
                                payload.gameId(),
                                payload.eraNumber(),
                                payload.roundNumber(),
                                toBufferedAction(payload, envelope));
                    }
                });
        GameEventIngestion.accept(message, ACTION_ROUND_CLOSED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload =
                            GameEventPayloads.read(objectMapper, message.getPayload(), ActionRoundClosedPayload.class);
                    replayRoundActions.replay(payload.gameId(), payload.eraNumber(), payload.roundNumber());
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
        GameEventIngestion.accept(message, ACTIVIST_DECLARATION_RECORDED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), ActivistDeclarationRecordedPayload.class);
                    // Handled in this same consumer group, not a standalone one (design.md
                    // "ActivistDeclarationRecorded is consumed by the existing
                    // CardPlayedAndResolutionKafkaConsumer, not a new class"): a lagging separate group could
                    // let this era's Round 1 ActionRoundClosed replay run before a RALLY declaration is
                    // durably buffered, or before a MOMENTUM bonus is applied.
                    if (DECLARATION_MODE_MOMENTUM.equals(payload.mode())) {
                        applyMomentumBonus.apply(payload.targetEventId(), payload.targetOutcomeId());
                    } else if (DECLARATION_MODE_RALLY.equals(payload.mode())) {
                        // Buffered into round 1's own buffer, alongside that round's CardPlayed/
                        // SpecialActionPlayed entries — ReplayRoundActionsCommandHandler consults it as a
                        // Round 1 magnitude modifier, never applies it as an action of its own. A same-round
                        // NULLIFY may cancel it under the same generic "nearest still-live action" rule that
                        // already governs SEAL/ANNIHILATE/CORRUPT/MIMIC — no special exclusion, consistent
                        // with this engine's existing NULLIFY semantics.
                        buffer.save(payload.gameId(), payload.eraNumber(), 1, toBufferedAction(payload, envelope));
                    }
                });
    }

    private static BufferedAction toBufferedAction(CardPlayedPayload payload, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                payload.cardType(),
                null,
                payload.playerId(),
                payload.cardInstanceId(),
                payload.targetEventId(),
                payload.sourceOutcomeId(),
                payload.targetOutcomeId(),
                null,
                envelope.occurredAt(),
                envelope.eventId());
    }

    private static BufferedAction toBufferedAction(SpecialActionPlayedPayload payload, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                payload.specialAction(),
                payload.playerId(),
                null,
                payload.targetEventId(),
                null,
                payload.targetOutcomeId(),
                payload.targetPlayerId(),
                envelope.occurredAt(),
                envelope.eventId());
    }

    private static BufferedAction toBufferedAction(
            ActivistDeclarationRecordedPayload payload, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                DECLARATION_MODE_RALLY,
                payload.playerId(),
                null,
                payload.targetEventId(),
                null,
                payload.targetOutcomeId(),
                null,
                envelope.occurredAt(),
                envelope.eventId());
    }
}
