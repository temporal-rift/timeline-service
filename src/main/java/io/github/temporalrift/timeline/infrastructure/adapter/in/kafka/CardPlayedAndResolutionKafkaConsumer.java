package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Set;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ActionRoundClosedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ActivistDeclarationMode;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ActivistDeclarationRecordedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.CardPlayedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.CardType;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ParadoxResolutionCardPlayedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.SpecialAction;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.EraEndedPayload;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.GameEndedPayload;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.ResolutionStartedPayload;
import io.github.temporalrift.timeline.application.port.in.ApplyMomentumBonusUseCase;
import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;
import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;

/**
 * Consumes {@code CardPlayed}, {@code SpecialActionPlayed}, {@code ActionRoundClosed}, {@code ResolutionStarted},
 * {@code ParadoxResolutionCardPlayed}, {@code ActivistDeclarationRecorded}, {@code EraEnded}, and {@code GameEnded}
 * from {@code game.events} in one Kafka consumer group: a single {@code @KafkaListener} reading one assigned
 * partition processes records strictly in the order {@code game-service} produced them, so a round's buffered
 * actions (including a Rally declaration) are durably recorded before that round's {@code ActionRoundClosed}
 * replays them in priority-tier order, every era's replayed effects are applied before that era's
 * {@code ResolutionStarted} is handled, a resolution-phase submission is applied in the order it was played, and
 * SCAN entitlement cleanup on {@code EraEnded}/{@code GameEnded} can never run ahead of the round replay that
 * created the entitlements it must remove. Splitting these into independent consumer groups would let a lagging
 * one be overtaken by a faster one — reachable in practice (consumer rebalance, GC pause, retry), not just
 * theoretical — silently losing or misordering an effect.
 *
 * <p>The Weaver chain saga's {@code THREAD}/{@code TAPESTRY}/{@code UNRAVEL} plays and {@code GameEnded}
 * termination join this same group for the same reason: a THREAD must validate against durably resolved
 * outcomes, never overtake the resolution that records them, and a game-end must not close chains ahead of
 * the last round's replay.
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
    private static final GameEventIngestion.Spec ERA_ENDED_SPEC =
            new GameEventIngestion.Spec("EraEnded", "futureevent.era-ended", 1);
    private static final GameEventIngestion.Spec GAME_ENDED_SPEC =
            new GameEventIngestion.Spec("GameEnded", "futureevent.game-ended", 1);
    private static final GameEventIngestion.Spec WEAVER_SAGA_SPECIAL_SPEC =
            new GameEventIngestion.Spec("SpecialActionPlayed", "weaverchain.saga-special", 1);
    private static final GameEventIngestion.Spec WEAVER_SAGA_GAME_ENDED_SPEC =
            new GameEventIngestion.Spec("GameEnded", "weaverchain.game-ended", 1);

    // The generated CardType/SpecialAction enums carry every value declared across the whole action-event
    // contract, including ones this consumer intentionally doesn't buffer (e.g. CardType.STABILIZE/DETONATE,
    // SpecialAction.CASCADE/FORESIGHT/... are not yet wired into replay). Filtering to exactly this set preserves
    // that existing scope -- it is not the same thing as "every type the schema knows about."
    private static final Set<CardType> KNOWN_CARD_TYPES = Set.of(
            CardType.PUSH,
            CardType.SUPPRESS,
            CardType.SWING,
            CardType.COLLIDE,
            CardType.AMPLIFY,
            CardType.NULLIFY,
            CardType.REDIRECT,
            CardType.STALL,
            CardType.INTERCEPT,
            CardType.SCAN,
            CardType.TRACE,
            CardType.DECOY,
            CardType.JAM);

    private static final Set<SpecialAction> KNOWN_SPECIAL_ACTIONS =
            Set.of(SpecialAction.SEAL, SpecialAction.ANNIHILATE, SpecialAction.CORRUPT, SpecialAction.MIMIC);

    // Only these CardPlayed types ever consult grade in resolution (graded-magnitude-resolution capability) —
    // an unrecognized/missing wire grade must not block buffering every other known card type too.
    private static final Set<CardType> GRADE_BEARING_CARD_TYPES =
            Set.of(CardType.PUSH, CardType.SUPPRESS, CardType.SWING, CardType.AMPLIFY);

    // Of ParadoxResolutionCardPlayed's eligible types (PUSH, SUPPRESS, STABILIZE, DETONATE), only these two
    // consult grade — STABILIZE/DETONATE must not be dropped over an unrecognized/missing wire grade.
    private static final Set<CardType> GRADE_BEARING_PARADOX_CARD_TYPES = Set.of(CardType.PUSH, CardType.SUPPRESS);

    private final ProcessedEventPort processedEvents;
    private final RoundActionBufferPort buffer;
    private final ReplayRoundActionsUseCase replayRoundActions;
    private final ResolveEraUseCase resolveEra;
    private final PlayParadoxResolutionCardUseCase playParadoxResolutionCard;
    private final ApplyMomentumBonusUseCase applyMomentumBonus;
    private final WeaverChainSagaUseCase weaverChainSaga;
    private final ScanEntitlementPort scanEntitlements;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            RoundActionBufferPort buffer,
            ReplayRoundActionsUseCase replayRoundActions,
            ResolveEraUseCase resolveEra,
            PlayParadoxResolutionCardUseCase playParadoxResolutionCard,
            ApplyMomentumBonusUseCase applyMomentumBonus,
            WeaverChainSagaUseCase weaverChainSaga,
            ScanEntitlementPort scanEntitlements,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.buffer = buffer;
        this.replayRoundActions = replayRoundActions;
        this.resolveEra = resolveEra;
        this.playParadoxResolutionCard = playParadoxResolutionCard;
        this.applyMomentumBonus = applyMomentumBonus;
        this.weaverChainSaga = weaverChainSaga;
        this.scanEntitlements = scanEntitlements;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "game.events", groupId = GROUP_ID)
    @Transactional(propagation = REQUIRES_NEW)
    public void handle(Message<Object> message) {
        GameEventIngestion.accept(message, CARD_PLAYED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), CardPlayedPayload.class);
            if (payload.cardType() == null || !KNOWN_CARD_TYPES.contains(payload.cardType())) {
                return;
            }
            var grade = toGrade(payload.grade());
            if (GRADE_BEARING_CARD_TYPES.contains(payload.cardType()) && grade == null) {
                // Unrecognized/missing grade on a grade-bearing type — safely skip only this card, not every
                // other known cardType, which never consults grade at all (graded-magnitude-resolution).
                return;
            }
            buffer.save(
                    payload.gameId(),
                    payload.eraNumber(),
                    payload.roundNumber(),
                    toBufferedAction(payload, grade, envelope));
        });
        GameEventIngestion.accept(message, SPECIAL_ACTION_PLAYED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    // Player-targeting specials (CORRUPT, UNRAVEL, ...) carry no event target, which
                    // the generated payload type wrongly requires — read the lenient shape instead.
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), SpecialActionPlayedInbound.class);
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
                    var grade = toGrade(payload.grade());
                    if (GRADE_BEARING_PARADOX_CARD_TYPES.contains(payload.cardType()) && grade == null) {
                        // Unrecognized/missing grade on PUSH/SUPPRESS only — STABILIZE/DETONATE never consult
                        // grade and must still be recorded (graded-magnitude-resolution).
                        return;
                    }
                    playParadoxResolutionCard.play(
                            payload.gameId(),
                            payload.eraNumber(),
                            payload.playerId(),
                            payload.cardType().name(),
                            grade,
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
                    if (payload.mode() == ActivistDeclarationMode.MOMENTUM) {
                        applyMomentumBonus.apply(payload.targetEventId(), payload.targetOutcomeId());
                    } else if (payload.mode() == ActivistDeclarationMode.RALLY) {
                        // Buffered into round 1's own buffer, alongside that round's CardPlayed/
                        // SpecialActionPlayed entries — ReplayRoundActionsCommandHandler consults it as a
                        // Round 1 magnitude modifier, never applies it as an action of its own. A same-round
                        // NULLIFY may cancel it under the same generic "nearest still-live action" rule that
                        // already governs SEAL/ANNIHILATE/CORRUPT/MIMIC — no special exclusion, consistent
                        // with this engine's existing NULLIFY semantics.
                        buffer.save(payload.gameId(), payload.eraNumber(), 1, toBufferedAction(payload, envelope));
                    }
                });
        GameEventIngestion.accept(message, ERA_ENDED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), EraEndedPayload.class);
            // Runs in this same consumer group so it can never overtake the round replay that created this
            // game/era's entitlements. Idempotent: harmless on redelivery.
            scanEntitlements.deleteByGameAndEra(payload.gameId(), payload.eraNumber());
        });
        GameEventIngestion.accept(message, GAME_ENDED_SPEC, processedEvents).ifPresent(envelope -> {
            var payload = GameEventPayloads.read(objectMapper, message.getPayload(), GameEndedPayload.class);
            // A final game can end without a following EraEnded, so this removes whatever the last era left
            // behind too. Idempotent: harmless on redelivery.
            scanEntitlements.deleteByGame(payload.gameId());
        });
        GameEventIngestion.accept(message, WEAVER_SAGA_SPECIAL_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(
                            objectMapper, message.getPayload(), SpecialActionPlayedInbound.class);
                    if (payload.specialAction() == null) {
                        return;
                    }
                    switch (payload.specialAction()) {
                        case THREAD -> {
                            if (payload.targetEventId() != null && payload.targetOutcomeId() != null) {
                                weaverChainSaga.playThread(
                                        payload.gameId(),
                                        payload.eraNumber(),
                                        payload.playerId(),
                                        payload.targetEventId(),
                                        payload.targetOutcomeId());
                            }
                        }
                        case TAPESTRY ->
                            weaverChainSaga.playTapestry(payload.gameId(), payload.eraNumber(), payload.playerId());
                        case UNRAVEL -> {
                            if (payload.targetPlayerId() != null) {
                                weaverChainSaga.playUnravel(
                                        payload.gameId(),
                                        payload.eraNumber(),
                                        payload.playerId(),
                                        payload.targetPlayerId());
                            }
                        }
                        default -> {
                            // SEAL/ANNIHILATE/CORRUPT/MIMIC replay through the round buffer; every other
                            // special stays a same-slice no-op at this consumer.
                        }
                    }
                });
        GameEventIngestion.accept(message, WEAVER_SAGA_GAME_ENDED_SPEC, processedEvents)
                .ifPresent(envelope -> {
                    var payload = GameEventPayloads.read(objectMapper, message.getPayload(), GameEndedPayload.class);
                    weaverChainSaga.endGame(payload.gameId());
                });
    }

    private static BufferedAction toBufferedAction(
            CardPlayedPayload payload, CardGrade grade, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                payload.cardType().name(),
                null,
                payload.playerId(),
                payload.cardInstanceId(),
                payload.targetEventId(),
                payload.targetEventIds(),
                payload.sourceOutcomeId(),
                payload.targetOutcomeId(),
                payload.targetPlayerId(),
                grade,
                envelope.occurredAt(),
                envelope.eventId());
    }

    private static BufferedAction toBufferedAction(SpecialActionPlayedInbound payload, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                payload.specialAction().name(),
                payload.playerId(),
                null,
                payload.targetEventId(),
                null,
                null,
                payload.targetOutcomeId(),
                payload.targetPlayerId(),
                null,
                envelope.occurredAt(),
                envelope.eventId());
    }

    private static BufferedAction toBufferedAction(
            ActivistDeclarationRecordedPayload payload, GameEventEnvelope envelope) {
        return new BufferedAction(
                ActionKind.SPECIAL_ACTION_PLAYED,
                null,
                ActivistDeclarationMode.RALLY.name(),
                payload.playerId(),
                null,
                payload.targetEventId(),
                null,
                null,
                payload.targetOutcomeId(),
                null,
                null,
                envelope.occurredAt(),
                envelope.eventId());
    }

    /**
     * Maps the wire {@code CardGrade} (which normalizes any unrecognized value to {@code UNKNOWN}, never fails
     * deserialization) to this service's domain {@link CardGrade} — {@code null} for {@code UNKNOWN} or a missing
     * grade, treated by callers exactly like an unknown {@code cardType}/{@code specialAction}: not buffered.
     */
    private static CardGrade toGrade(
            io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.CardGrade wireGrade) {
        if (wireGrade == null) {
            return null;
        }
        return switch (wireGrade) {
            case I -> CardGrade.I;
            case II -> CardGrade.II;
            case III -> CardGrade.III;
            case UNKNOWN -> null;
        };
    }
}
