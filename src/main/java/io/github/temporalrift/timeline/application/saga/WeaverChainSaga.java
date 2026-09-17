package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionArmedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionConsumedEvent;
import io.github.temporalrift.timeline.domain.event.ChainReAnchored;
import io.github.temporalrift.timeline.domain.event.ChainReAnchoredEvent;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainEvent;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ChainLink;
import io.github.temporalrift.timeline.domain.weaverchain.InvalidChainLinkException;
import io.github.temporalrift.timeline.domain.weaverchain.ResolvedOutcome;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

/**
 * Cross-era saga driving the {@code WeaverChain} aggregate from Weaver special plays. The saga stays open
 * across era boundaries until the third link completes it, or the game ends. All state is durable: chain
 * links in the aggregate stream, orchestration flags in the saga table — a restart resumes from what is
 * persisted, with no in-memory timers or caches.
 */
@Service
class WeaverChainSaga implements WeaverChainSagaUseCase {

    private static final String AGGREGATE_TYPE = "WeaverChain";

    private static final String SPECIAL_TAPESTRY = "TAPESTRY";
    private static final String SPECIAL_REWEAVE = "REWEAVE";

    private static final String REASON_DID_NOT_RESOLVE = "OUTCOME_DID_NOT_RESOLVE";
    private static final String REASON_ALREADY_LINKED = "EVENT_ALREADY_LINKED";
    private static final String REASON_MISSING_COORDINATE = "MISSING_COORDINATE";
    private static final String REASON_INVALID_SOURCE = "INVALID_SOURCE";

    private static final String REASON_NO_ACTIVE_CHAIN = "NO_ACTIVE_CHAIN";
    private static final String REASON_CHAIN_TOO_SHORT = "CHAIN_TOO_SHORT";
    private static final String REASON_ALREADY_USED_THIS_ERA = "ALREADY_USED_THIS_ERA";
    private static final String REASON_TARGET_NOT_RESOLVED = "TARGET_NOT_RESOLVED";
    private static final String REASON_TARGET_ALREADY_LINKED = "TARGET_ALREADY_LINKED";

    private final WeaverChainRepository chains;
    private final WeaverChainSagaRepository sagas;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final ProbabilityRulesPort probabilityRules;
    private final TimelineEventPublisher publisher;
    private final Clock clock;

    WeaverChainSaga(
            WeaverChainRepository chains,
            WeaverChainSagaRepository sagas,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            ProbabilityRulesPort probabilityRules,
            TimelineEventPublisher publisher,
            Clock clock) {
        this.chains = chains;
        this.sagas = sagas;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.probabilityRules = probabilityRules;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void playThread(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            UUID sourceEventId,
            UUID sourceOutcomeId,
            UUID targetEventId,
            UUID targetOutcomeId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        var source = new OutcomeCoordinate(sourceEventId, sourceOutcomeId);
        var target = new OutcomeCoordinate(targetEventId, targetOutcomeId);
        var rejectionReason = validateThread(gameId, eraNumber, source, target);
        if (rejectionReason != null) {
            publishThreadRejected(
                    gameId, eraNumber, saga == null ? null : saga.chainId(), playerId, source, target, rejectionReason);
            return;
        }
        acceptThread(gameId, eraNumber, playerId, saga, source, target);
    }

    /** The causal-link validity rules THREAD alone can check before touching the chain aggregate. */
    private String validateThread(UUID gameId, int eraNumber, OutcomeCoordinate source, OutcomeCoordinate target) {
        if (source.eventId() == null
                || source.outcomeId() == null
                || target.eventId() == null
                || target.outcomeId() == null) {
            return REASON_MISSING_COORDINATE;
        }
        if (!isValidCurrentEraSource(gameId, eraNumber, source.eventId(), source.outcomeId())) {
            return REASON_INVALID_SOURCE;
        }
        if (!resolvedAs(target.eventId(), target.outcomeId())) {
            return REASON_DID_NOT_RESOLVE;
        }
        return null;
    }

    /** Grows (or starts) the chain once THREAD's coordinates are known valid. */
    private void acceptThread(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            WeaverChainSagaState saga,
            OutcomeCoordinate source,
            OutcomeCoordinate target) {
        UUID chainId = saga == null ? UUID.randomUUID() : saga.chainId();
        if (saga == null) {
            chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
            sagas.save(
                    new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null, null));
        }
        var chain = chains.findById(chainId);
        UUID previousLinkEventId = chain.links().isEmpty()
                ? null
                : chain.links().get(chain.links().size() - 1).eventId();
        List<? extends WeaverChainEvent> facts;
        try {
            facts = chain.addLink(
                    target.eventId(),
                    target.outcomeId(),
                    eraNumber,
                    Set.of(new ResolvedOutcome(target.eventId(), target.outcomeId())),
                    source.eventId(),
                    source.outcomeId());
        } catch (InvalidChainLinkException _) {
            publishThreadRejected(gameId, eraNumber, chainId, playerId, source, target, REASON_ALREADY_LINKED);
            return;
        }
        chains.appendAll(chainId, facts);
        applyThreadReward(source.eventId(), source.outcomeId());
        var grown = chains.findById(chainId);
        publisher.publish(TimelineEventEnvelope.create(
                chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainLinkAddedEvent(
                        gameId,
                        chainId,
                        playerId,
                        source.eventId(),
                        source.outcomeId(),
                        target.eventId(),
                        target.outcomeId(),
                        grown.length(),
                        previousLinkEventId),
                clock));
        if (facts.stream().anyMatch(ChainCompleted.class::isInstance)) {
            publisher.publish(TimelineEventEnvelope.create(
                    chainId,
                    AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ChainCompletedEvent(gameId, eraNumber, chainId, playerId, toEntries(grown.links())),
                    clock));
            sagas.save(new WeaverChainSagaState(
                    chainId,
                    gameId,
                    playerId,
                    WeaverChainSagaStatus.COMPLETED,
                    false,
                    saga == null ? null : saga.tapestryUsedEra(),
                    saga == null ? null : saga.reweaveUsedEra()));
        }
    }

    /**
     * The accepted link's reward: the configured favourable shift applied to the source outcome, through the
     * same transfer path (bounds, redistribution, sealed-outcome handling) as any other direct transfer.
     */
    private void applyThreadReward(UUID sourceEventId, UUID sourceOutcomeId) {
        var sourceEvent = futureEvents.findById(sourceEventId);
        var result = sourceEvent.applyShift(
                new ProbabilityShift.Push(sourceOutcomeId),
                probabilityRules.threadShift(),
                probabilityRules.probabilityFloor(),
                probabilityRules.probabilityCeiling());
        futureEvents.append(sourceEventId, result);
    }

    /** The source names a current-era, not-yet-resolved outcome — the only claim this saga can itself verify. */
    private boolean isValidCurrentEraSource(UUID gameId, int eraNumber, UUID sourceEventId, UUID sourceOutcomeId) {
        var currentEraEventIds = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(FutureEventEraIndexPort.IndexedEventId::eventId)
                .collect(Collectors.toSet());
        if (!currentEraEventIds.contains(sourceEventId)) {
            return false;
        }
        final FutureEvent sourceEvent;
        try {
            sourceEvent = futureEvents.findById(sourceEventId);
        } catch (FutureEventNotFoundException _) {
            return false;
        }
        if (sourceEvent.resolved()) {
            return false;
        }
        return sourceEvent.outcomes().stream().anyMatch(o -> o.outcomeId().equals(sourceOutcomeId));
    }

    @Override
    @Transactional
    public void playTapestry(UUID gameId, int eraNumber, UUID playerId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        if (saga == null) {
            publishSpecialRejected(
                    gameId,
                    eraNumber,
                    playerId,
                    SPECIAL_TAPESTRY,
                    null,
                    OutcomeCoordinate.NONE,
                    REASON_NO_ACTIVE_CHAIN);
            return;
        }
        if (Integer.valueOf(eraNumber).equals(saga.tapestryUsedEra())) {
            publishSpecialRejected(
                    gameId,
                    eraNumber,
                    playerId,
                    SPECIAL_TAPESTRY,
                    saga.chainId(),
                    OutcomeCoordinate.NONE,
                    REASON_ALREADY_USED_THIS_ERA);
            return;
        }
        var chain = chains.findById(saga.chainId());
        if (chain.length() < 2) {
            publishSpecialRejected(
                    gameId,
                    eraNumber,
                    playerId,
                    SPECIAL_TAPESTRY,
                    saga.chainId(),
                    OutcomeCoordinate.NONE,
                    REASON_CHAIN_TOO_SHORT);
            return;
        }
        sagas.save(new WeaverChainSagaState(
                saga.chainId(), gameId, playerId, WeaverChainSagaStatus.OPEN, true, eraNumber, saga.reweaveUsedEra()));
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainProtectionArmedEvent(gameId, eraNumber, saga.chainId(), playerId),
                clock));
    }

    @Override
    @Transactional
    public void playReweave(UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        var target = new OutcomeCoordinate(targetEventId, targetOutcomeId);
        if (saga == null) {
            publishSpecialRejected(gameId, eraNumber, playerId, SPECIAL_REWEAVE, null, target, REASON_NO_ACTIVE_CHAIN);
            return;
        }
        var rejectionReason = validateReweave(saga, eraNumber, target);
        if (rejectionReason != null) {
            publishSpecialRejected(
                    gameId, eraNumber, playerId, SPECIAL_REWEAVE, saga.chainId(), target, rejectionReason);
            return;
        }
        acceptReweave(gameId, eraNumber, playerId, saga, target);
    }

    /** The rules REWEAVE alone can check before touching the chain aggregate. */
    private String validateReweave(WeaverChainSagaState saga, int eraNumber, OutcomeCoordinate target) {
        if (Integer.valueOf(eraNumber).equals(saga.reweaveUsedEra())) {
            return REASON_ALREADY_USED_THIS_ERA;
        }
        if (target.eventId() == null || target.outcomeId() == null) {
            return REASON_MISSING_COORDINATE;
        }
        if (!resolvedAs(target.eventId(), target.outcomeId())) {
            return REASON_TARGET_NOT_RESOLVED;
        }
        return null;
    }

    /** Re-anchors the chain's newest link once REWEAVE's target is known valid. */
    private void acceptReweave(
            UUID gameId, int eraNumber, UUID playerId, WeaverChainSagaState saga, OutcomeCoordinate target) {
        var chain = chains.findById(saga.chainId());
        var fact = tryReAnchor(chain, target.eventId(), target.outcomeId(), eraNumber);
        if (fact == null) {
            publishSpecialRejected(
                    gameId, eraNumber, playerId, SPECIAL_REWEAVE, saga.chainId(), target, REASON_TARGET_ALREADY_LINKED);
            return;
        }
        chains.append(saga.chainId(), fact);
        var reAnchored = chains.findById(saga.chainId());
        sagas.save(new WeaverChainSagaState(
                saga.chainId(),
                gameId,
                playerId,
                WeaverChainSagaStatus.OPEN,
                saga.tapestryProtected(),
                saga.tapestryUsedEra(),
                eraNumber));
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainReAnchoredEvent(
                        gameId,
                        eraNumber,
                        saga.chainId(),
                        playerId,
                        fact.discardedEventId(),
                        fact.discardedOutcomeId(),
                        target.eventId(),
                        target.outcomeId(),
                        reAnchored.length()),
                clock));
    }

    private ChainReAnchored tryReAnchor(WeaverChain chain, UUID targetEventId, UUID targetOutcomeId, int eraNumber) {
        try {
            return chain.reAnchor(
                    targetEventId,
                    targetOutcomeId,
                    eraNumber,
                    Set.of(new ResolvedOutcome(targetEventId, targetOutcomeId)));
        } catch (InvalidChainLinkException _) {
            return null;
        }
    }

    @Override
    @Transactional
    public void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            invalidateLinkedOutcome(saga, gameId, eraNumber, targetEventId, targetOutcomeId);
        }
    }

    private void invalidateLinkedOutcome(
            WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId) {
        var chain = chains.findById(saga.chainId());
        boolean linked = chain.links().stream()
                .anyMatch(link ->
                        link.eventId().equals(targetEventId) && link.outcomeId().equals(targetOutcomeId));
        if (!linked) {
            return;
        }
        // Protection is scoped to the era it was armed in — an unconsumed TAPESTRY from an earlier era
        // must not still be treated as active protection (confirmed bug: this previously checked only
        // the boolean flag, never the era it was armed for).
        boolean protectedNow =
                saga.tapestryProtected() && Integer.valueOf(eraNumber).equals(saga.tapestryUsedEra());
        if (protectedNow) {
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.OPEN,
                    false,
                    saga.tapestryUsedEra(),
                    saga.reweaveUsedEra()));
            publisher.publish(TimelineEventEnvelope.create(
                    saga.chainId(),
                    AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ChainProtectionConsumedEvent(
                            gameId, eraNumber, saga.chainId(), saga.playerId(), targetEventId, targetOutcomeId),
                    clock));
            return;
        }
        var invalidated = chain.invalidateLink(targetEventId, targetOutcomeId);
        if (invalidated.isEmpty()) {
            return;
        }
        chains.append(saga.chainId(), invalidated.get());
        var current = chains.findById(saga.chainId());
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainLinkInvalidatedEvent(
                        gameId,
                        eraNumber,
                        saga.chainId(),
                        saga.playerId(),
                        targetEventId,
                        targetOutcomeId,
                        current.length()),
                clock));
    }

    @Override
    @Transactional
    public void endGame(UUID gameId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.ENDED,
                    false,
                    saga.tapestryUsedEra(),
                    saga.reweaveUsedEra()));
        }
    }

    /**
     * Whether the referenced outcome actually resolved as the winner of its event — the causal-link
     * validity rule. Unknown events, unresolved events, and resolved-differently outcomes all fail.
     */
    private boolean resolvedAs(UUID eventId, UUID outcomeId) {
        final FutureEvent futureEvent;
        try {
            futureEvent = futureEvents.findById(eventId);
        } catch (FutureEventNotFoundException _) {
            return false;
        }
        if (!futureEvent.resolved()) {
            return false;
        }
        return Objects.equals(winningOutcomeId(futureEvent.outcomes()), outcomeId);
    }

    /** Highest-probability non-annihilated outcome, ties broken by smallest outcomeId — as resolved. */
    private static UUID winningOutcomeId(List<Outcome> outcomes) {
        return outcomes.stream()
                .filter(outcome -> !outcome.annihilated())
                .max(Comparator.comparingInt(Outcome::probability)
                        .thenComparing(Comparator.comparing(Outcome::outcomeId).reversed()))
                .map(Outcome::outcomeId)
                .orElse(null);
    }

    private static List<ChainCompletedEvent.ChainLinkEntry> toEntries(List<ChainLink> links) {
        return links.stream()
                .map(link -> new ChainCompletedEvent.ChainLinkEntry(link.eventId(), link.outcomeId(), link.eraNumber()))
                .toList();
    }

    private void publishThreadRejected(
            UUID gameId,
            int eraNumber,
            UUID chainId,
            UUID playerId,
            OutcomeCoordinate source,
            OutcomeCoordinate target,
            String reason) {
        publisher.publish(TimelineEventEnvelope.create(
                chainId == null ? playerId : chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ThreadRejectedEvent(
                        gameId,
                        eraNumber,
                        chainId,
                        playerId,
                        source.eventId(),
                        source.outcomeId(),
                        target.eventId(),
                        target.outcomeId(),
                        reason),
                clock));
    }

    private void publishSpecialRejected(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            String specialAction,
            UUID chainId,
            OutcomeCoordinate target,
            String reason) {
        publisher.publish(TimelineEventEnvelope.create(
                chainId == null ? playerId : chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new SpecialRejectedEvent(
                        gameId,
                        eraNumber,
                        playerId,
                        specialAction,
                        chainId,
                        target.eventId(),
                        target.outcomeId(),
                        reason),
                clock));
    }

    /** A (possibly unvalidated, possibly null) event/outcome pair — reduces the parameter count of the
     * rejection-publishing helpers shared by THREAD, TAPESTRY and REWEAVE. */
    private record OutcomeCoordinate(UUID eventId, UUID outcomeId) {
        private static final OutcomeCoordinate NONE = new OutcomeCoordinate(null, null);
    }
}
