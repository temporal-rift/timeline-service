package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EventFence;
import io.github.temporalrift.timeline.domain.port.out.GameScope;
import io.github.temporalrift.timeline.domain.port.out.ProcessedEventPort;

/**
 * Shared envelope-validation prologue for the {@code game.events} consumers: filter by the stable
 * {@code eventType} header, reject a record outside the hosted game, discard a missing {@code eventId}, skip an
 * unsupported version, then claim the {@code eventId}.
 * A rejected record is consumed normally — no handler runs and no claim is recorded — so its consumer group's offset
 * still advances and a late record of another game can never act.
 */
@Component
class GameEventIngestion {

    private static final Logger log = LoggerFactory.getLogger(GameEventIngestion.class);
    private static final Set<String> KNOWN_GAME_EVENT_TYPES = Set.of(
            "LobbyCreated",
            "PlayerJoinedLobby",
            "PlayerLeftLobby",
            "HostTransferred",
            "LobbyClosed",
            "FactionsDrawn",
            "FactionAssigned",
            "GameStarted",
            "GameStartFailed",
            "GameStartCancelled",
            "PlayerDisconnected",
            "PlayerAbandoned",
            "EraStarted",
            "EraEnded",
            "EraFailed",
            "GameEndedAbnormally",
            "GameEnded",
            "TimelineCollapsed",
            "TimelineStabilized",
            "WinConditionMet",
            "FactionRevealed",
            "EventsDrawn",
            "HandDealt",
            "HandSelected",
            "ResolutionStarted",
            "DeclarationWindowOpened",
            "DeclarationOptionsOffered",
            "ActionRoundStarted",
            "CardPlayed",
            "ActionRoundPassed",
            "ParadoxResolutionCardsOffered",
            "ParadoxResolutionCardPlayed",
            "ParadoxResolutionPassed",
            "SpecialActionPlayed",
            "PlayerJammed",
            "InfluenceTraced",
            "HandCardIntercepted",
            "ActionRoundTimerExpired",
            "PlayerSkipped",
            "ActionRoundClosed",
            "RoundSummaryPublished",
            "BandedProbabilityPublished",
            "ActivistDeclarationRecorded",
            "ExposeSignatureRevealed",
            "ExposeBehaviorChanged");

    private final ProcessedEventPort processedEvents;
    private final GameEventSkipMetrics skipMetrics;
    private final EventFence eventFence;
    private final GameScope gameScope;

    GameEventIngestion(
            ProcessedEventPort processedEvents,
            GameEventSkipMetrics skipMetrics,
            EventFence eventFence,
            GameScope gameScope) {
        this.processedEvents = processedEvents;
        this.skipMetrics = skipMetrics;
        this.eventFence = eventFence;
        this.gameScope = gameScope;
    }

    /**
     * @return the envelope if the message matches {@code spec}'s event type, belongs to the hosted game, is
     *     well-formed, has a supported version, and was newly claimed for {@code spec}'s consumer; empty if the
     *     caller should do nothing further (the reason, if any, is already logged here).
     */
    Optional<GameEventEnvelope> accept(Message<Object> message, Spec spec) {
        var envelope = GameEventEnvelope.from(message);
        if (!matches(envelope, spec)) {
            recordUnknownType(envelope, spec);
            return Optional.empty();
        }
        if (isFenced(message)) {
            log.debug("Ignoring {} event {} from before the last close", spec.eventName(), envelope.eventId());
            return Optional.empty();
        }
        if (!gameScope.admits(envelope.gameId())) {
            log.debug(
                    "Ignoring {} event {} of game {} outside the hosted execution",
                    spec.eventName(),
                    envelope.eventId(),
                    envelope.gameId());
            return Optional.empty();
        }
        if (envelope.eventId() == null) {
            log.warn("Malformed {} envelope (missing eventId) — discarding", spec.eventName());
            return Optional.empty();
        }
        if (envelope.version() == null || envelope.version() != spec.supportedVersion()) {
            skipMetrics.recordUnsupportedVersion(spec.consumer());
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

    /** A record still in the source when a context closed belongs to that context, whatever its game id. */
    private boolean isFenced(Message<Object> message) {
        var headers = message.getHeaders();
        var topic = headers.get(KafkaHeaders.RECEIVED_TOPIC, String.class);
        var partition = headers.get(KafkaHeaders.RECEIVED_PARTITION, Integer.class);
        var offset = headers.get(KafkaHeaders.OFFSET, Long.class);
        return topic != null && partition != null && offset != null && eventFence.isFenced(topic, partition, offset);
    }

    private static boolean matches(GameEventEnvelope envelope, Spec spec) {
        return spec.eventName().equals(envelope.eventType());
    }

    private void recordUnknownType(GameEventEnvelope envelope, Spec spec) {
        if (spec.observesUnknownTypes()
                && envelope.eventType() != null
                && !KNOWN_GAME_EVENT_TYPES.contains(envelope.eventType())) {
            log.warn("Unsupported game event type {} — skipping", envelope.eventType());
            skipMetrics.recordUnknownType();
        }
    }

    record Spec(String eventName, String consumer, int supportedVersion, boolean observesUnknownTypes) {

        Spec(String eventName, String consumer, int supportedVersion) {
            this(eventName, consumer, supportedVersion, false);
        }
    }
}
