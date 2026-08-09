package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.Set;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.application.port.in.ReplayRoundActionsUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

/**
 * Consumes {@code CardPlayed}, {@code SpecialActionPlayed}, {@code ActionRoundClosed}, {@code ResolutionStarted},
 * and {@code ParadoxResolutionCardPlayed} from {@code game.events} in one Kafka consumer group (design.md Decision
 * 1/3 of timeline-mvp4-card-modifiers, revised after PR #25 review; extended by timeline-mvp5-faction-specials
 * Decision 2, timeline-mvp8-paradox-completion, and superseded for {@code CardPlayed}/{@code SpecialActionPlayed}
 * by timeline-mvp9-resolution-ordering-paradox-cards design.md Decision 1): a single {@code @KafkaListener}
 * reading one assigned partition processes records strictly in the order {@code game-service} produced them, so
 * a round's buffered actions are durably recorded before that round's {@code ActionRoundClosed} replays them in
 * priority-tier order, every era's replayed effects are applied before that era's {@code ResolutionStarted} is
 * handled, and a resolution-phase submission is applied in the order it was played. Splitting these into
 * independent consumer groups would let a lagging one be overtaken by a faster one — reachable in practice
 * (consumer rebalance, GC pause, retry), not just theoretical — silently losing or misordering an effect.
 * {@code ParadoxResolutionCardPlayed} has no published {@code game.events} contract yet (temporal-rift/apis#24,
 * non-blocking) — its payload is hand-rolled against the documented shape, same as every other message this
 * consumer already handles.
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

    private static final Set<String> KNOWN_SPECIAL_ACTIONS = Set.of("SEAL", "ANNIHILATE", "CORRUPT");

    private final ProcessedEventPort processedEvents;
    private final RoundActionBufferPort buffer;
    private final ReplayRoundActionsUseCase replayRoundActions;
    private final ResolveEraUseCase resolveEra;
    private final PlayParadoxResolutionCardUseCase playParadoxResolutionCard;
    private final ObjectMapper objectMapper;

    CardPlayedAndResolutionKafkaConsumer(
            ProcessedEventPort processedEvents,
            RoundActionBufferPort buffer,
            ReplayRoundActionsUseCase replayRoundActions,
            ResolveEraUseCase resolveEra,
            PlayParadoxResolutionCardUseCase playParadoxResolutionCard,
            ObjectMapper objectMapper) {
        this.processedEvents = processedEvents;
        this.buffer = buffer;
        this.replayRoundActions = replayRoundActions;
        this.resolveEra = resolveEra;
        this.playParadoxResolutionCard = playParadoxResolutionCard;
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
}
