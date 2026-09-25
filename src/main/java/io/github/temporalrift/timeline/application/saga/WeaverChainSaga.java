package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompleted;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreadedEvent;
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
import io.github.temporalrift.timeline.domain.weaverchain.LinkEraNotSuccessiveException;
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

    private static final String REASON_MISSING_COORDINATE = "MISSING_COORDINATE";
    private static final String REASON_INVALID_COORDINATE = "INVALID_COORDINATE";
    private static final String REASON_ALREADY_PENDING = "ALREADY_PENDING";
    private static final String REASON_ALREADY_LINKED = "EVENT_ALREADY_LINKED";
    private static final String REASON_LINK_ERA_NOT_SUCCESSIVE = "LINK_ERA_NOT_SUCCESSIVE";
    private static final String REASON_CHAIN_ALREADY_COMPLETED = "CHAIN_ALREADY_COMPLETED";

    private static final String REASON_NO_ACTIVE_CHAIN = "NO_ACTIVE_CHAIN";
    private static final String REASON_CHAIN_TOO_SHORT = "CHAIN_TOO_SHORT";
    private static final String REASON_ALREADY_USED_THIS_ERA = "ALREADY_USED_THIS_ERA";
    private static final String REASON_TARGET_NOT_RESOLVED = "TARGET_NOT_RESOLVED";
    private static final String REASON_TARGET_ALREADY_LINKED = "TARGET_ALREADY_LINKED";
    private static final String REASON_NO_LINK_TO_REPLACE = "NO_LINK_TO_REPLACE";

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
    public void playThread(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        var coordinate = new OutcomeCoordinate(eventId, outcomeId);
        sagas.findCompletedByGameAndPlayer(gameId, playerId)
                .ifPresentOrElse(
                        completed -> publishThreadRejected(
                                gameId,
                                eraNumber,
                                completed.chainId(),
                                playerId,
                                coordinate,
                                REASON_CHAIN_ALREADY_COMPLETED),
                        () -> playThreadWithoutCompletedChain(gameId, eraNumber, playerId, coordinate));
    }

    private void playThreadWithoutCompletedChain(
            UUID gameId, int eraNumber, UUID playerId, OutcomeCoordinate coordinate) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        var rejectionReason = validateThread(gameId, eraNumber, coordinate);
        if (rejectionReason != null) {
            publishThreadRejected(
                    gameId, eraNumber, saga == null ? null : saga.chainId(), playerId, coordinate, rejectionReason);
            return;
        }
        acceptThread(gameId, eraNumber, playerId, saga, coordinate);
    }

    /** The causal-link validity rules THREAD alone can check before touching the chain aggregate. */
    private String validateThread(UUID gameId, int eraNumber, OutcomeCoordinate coordinate) {
        if (coordinate.eventId() == null || coordinate.outcomeId() == null) {
            return REASON_MISSING_COORDINATE;
        }
        if (!isValidCurrentEraCoordinate(gameId, eraNumber, coordinate.eventId(), coordinate.outcomeId())) {
            return REASON_INVALID_COORDINATE;
        }
        return null;
    }

    /** Opens (or starts) a pending link once THREAD's coordinate is known valid. */
    private void acceptThread(
            UUID gameId, int eraNumber, UUID playerId, WeaverChainSagaState saga, OutcomeCoordinate coordinate) {
        UUID chainId = saga == null ? UUID.randomUUID() : saga.chainId();
        if (saga == null) {
            chains.append(chainId, new WeaverChainStarted(chainId, playerId, gameId));
            sagas.save(
                    new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null, null));
        }
        var chain = chains.findById(chainId);
        try {
            var fact = chain.threadPendingLink(coordinate.eventId(), coordinate.outcomeId(), eraNumber);
            chains.append(chainId, fact);
        } catch (InvalidChainLinkException e) {
            var reason = threadRejectionReason(chain, e);
            publishThreadRejected(gameId, eraNumber, chainId, playerId, coordinate, reason);
            return;
        }
        applyThreadReward(coordinate.eventId(), coordinate.outcomeId());
        publisher.publish(TimelineEventEnvelope.create(
                chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainLinkThreadedEvent(
                        gameId, eraNumber, chainId, playerId, coordinate.eventId(), coordinate.outcomeId()),
                clock));
    }

    private static String threadRejectionReason(WeaverChain chain, InvalidChainLinkException rejection) {
        if (rejection instanceof LinkEraNotSuccessiveException) {
            return REASON_LINK_ERA_NOT_SUCCESSIVE;
        }
        return chain.pendingLink() != null ? REASON_ALREADY_PENDING : REASON_ALREADY_LINKED;
    }

    /**
     * The accepted link's reward: the configured favourable shift applied to the named outcome, through the
     * same transfer path (bounds, redistribution, sealed-outcome handling) as any other direct transfer.
     */
    private void applyThreadReward(UUID eventId, UUID outcomeId) {
        var futureEvent = futureEvents.findById(eventId);
        var result = futureEvent.applyShift(
                new ProbabilityShift.Push(outcomeId),
                probabilityRules.threadShift(),
                probabilityRules.probabilityFloor(),
                probabilityRules.probabilityCeiling());
        futureEvents.append(eventId, result);
    }

    /**
     * The coordinate names a current-era, not-yet-resolved, not-already-annihilated outcome — the only claim
     * this saga can itself verify. An annihilated outcome can never win, so it is as dead a bet as an already-
     * resolved one; accepting it would open a pending link with no path to Tapestry protection (the reactive
     * check in {@link #annihilateOutcome} only fires at the moment of annihilation, which has already passed).
     */
    private boolean isValidCurrentEraCoordinate(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        var currentEraEventIds = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(FutureEventEraIndexPort.IndexedEventId::eventId)
                .collect(Collectors.toSet());
        if (!currentEraEventIds.contains(eventId)) {
            return false;
        }
        final FutureEvent futureEvent;
        try {
            futureEvent = futureEvents.findById(eventId);
        } catch (FutureEventNotFoundException _) {
            return false;
        }
        if (futureEvent.resolved()) {
            return false;
        }
        return futureEvent.outcomes().stream().anyMatch(o -> o.outcomeId().equals(outcomeId) && !o.annihilated());
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
        resolvedTarget(target)
                .ifPresentOrElse(
                        resolved -> acceptReweave(gameId, eraNumber, playerId, saga, target, resolved),
                        () -> publishSpecialRejected(
                                gameId,
                                eraNumber,
                                playerId,
                                SPECIAL_REWEAVE,
                                saga.chainId(),
                                target,
                                REASON_TARGET_NOT_RESOLVED));
    }

    /** The rules REWEAVE alone can check before touching the chain aggregate. */
    private static String validateReweave(WeaverChainSagaState saga, int eraNumber, OutcomeCoordinate target) {
        if (Integer.valueOf(eraNumber).equals(saga.reweaveUsedEra())) {
            return REASON_ALREADY_USED_THIS_ERA;
        }
        if (target.eventId() == null || target.outcomeId() == null) {
            return REASON_MISSING_COORDINATE;
        }
        return null;
    }

    /** Re-anchors the chain's newest link once REWEAVE's target is known to have won its event. */
    private void acceptReweave(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            WeaverChainSagaState saga,
            OutcomeCoordinate target,
            ResolvedOutcome resolvedTarget) {
        var chain = chains.findById(saga.chainId());
        final List<WeaverChainEvent> facts;
        try {
            facts = chain.reAnchor(resolvedTarget);
        } catch (InvalidChainLinkException e) {
            publishSpecialRejected(
                    gameId,
                    eraNumber,
                    playerId,
                    SPECIAL_REWEAVE,
                    saga.chainId(),
                    target,
                    reweaveRejectionReason(chain, e));
            return;
        }
        chains.appendAll(saga.chainId(), facts);
        var reAnchored = chains.findById(saga.chainId());
        var fact = (ChainReAnchored) facts.getFirst();
        var completed = facts.stream().anyMatch(ChainCompleted.class::isInstance);
        sagas.save(new WeaverChainSagaState(
                saga.chainId(),
                gameId,
                playerId,
                completed ? WeaverChainSagaStatus.COMPLETED : WeaverChainSagaStatus.OPEN,
                !completed && saga.tapestryProtected(),
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
        if (completed) {
            publishChainCompleted(gameId, saga, reAnchored);
        }
    }

    private void publishChainCompleted(UUID gameId, WeaverChainSagaState saga, WeaverChain completed) {
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainCompletedEvent(
                        gameId,
                        completed.links().getLast().eraNumber(),
                        saga.chainId(),
                        saga.playerId(),
                        toEntries(completed.links())),
                clock));
    }

    private static String reweaveRejectionReason(WeaverChain chain, InvalidChainLinkException rejection) {
        if (rejection instanceof LinkEraNotSuccessiveException) {
            return REASON_LINK_ERA_NOT_SUCCESSIVE;
        }
        if (chain.pendingLink() == null && chain.length() == 0) {
            return REASON_NO_LINK_TO_REPLACE;
        }
        return REASON_TARGET_ALREADY_LINKED;
    }

    @Override
    @Transactional
    public void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            protectPendingLinkIfArmed(saga, gameId, eraNumber, targetEventId, targetOutcomeId);
        }
    }

    /**
     * An older-era pending link expires before matching the annihilated outcome. For a current-era match,
     * Tapestry consumes protection and confirms the link; otherwise the link remains pending for
     * {@code ParadoxDetector} at era resolution.
     */
    private void protectPendingLinkIfArmed(
            WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        var chain = chains.findById(saga.chainId());
        var pending = chain.pendingLink();
        if (pending == null) {
            return;
        }
        if (pending.eraNumber() != eraNumber) {
            if (pending.eraNumber() < eraNumber) {
                expirePendingLink(gameId, eraNumber, saga, chain);
            }
            return;
        }
        if (!pending.eventId().equals(eventId) || !pending.outcomeId().equals(outcomeId)) {
            return;
        }
        // Protection is scoped to the era it was armed in — an unconsumed TAPESTRY from an earlier era
        // must not still be treated as active protection.
        boolean protectedNow =
                saga.tapestryProtected() && Integer.valueOf(eraNumber).equals(saga.tapestryUsedEra());
        if (!protectedNow) {
            return;
        }
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
                        gameId, eraNumber, saga.chainId(), saga.playerId(), eventId, outcomeId),
                clock));
        confirmPendingLink(gameId, saga);
    }

    @Override
    @Transactional
    public void resolvePendingLink(UUID gameId, int eraNumber, UUID eventId, UUID winningOutcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            resolvePendingLinkForSaga(saga, gameId, eraNumber, eventId, winningOutcomeId);
        }
    }

    private void resolvePendingLinkForSaga(
            WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID eventId, UUID winningOutcomeId) {
        var chain = chains.findById(saga.chainId());
        var pending = chain.pendingLink();
        if (pending == null || !pending.eventId().equals(eventId)) {
            return;
        }
        if (pending.eraNumber() != eraNumber) {
            if (pending.eraNumber() < eraNumber) {
                expirePendingLink(gameId, eraNumber, saga, chain);
            }
            return;
        }
        if (pending.outcomeId().equals(winningOutcomeId)) {
            confirmPendingLink(gameId, saga);
        } else {
            clearPendingLink(gameId, eraNumber, saga, chain);
        }
    }

    @Override
    @Transactional
    public void stallPendingLink(UUID gameId, int eraNumber, UUID eventId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            var pending = chain.pendingLink();
            if (pending == null || !pending.eventId().equals(eventId) || pending.eraNumber() != eraNumber) {
                continue;
            }
            expirePendingLink(gameId, eraNumber, saga, chain);
        }
    }

    @Override
    @Transactional
    public void confirmParadoxResolvedLink(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            var pending = chain.pendingLink();
            if (pending != null
                    && pending.eventId().equals(eventId)
                    && pending.outcomeId().equals(outcomeId)) {
                if (pending.eraNumber() == eraNumber) {
                    confirmPendingLink(gameId, saga);
                } else if (pending.eraNumber() < eraNumber) {
                    expirePendingLink(gameId, eraNumber, saga, chain);
                }
            }
        }
    }

    @Override
    @Transactional
    public void breakChainOnCascadedParadox(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, UUID paradoxId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            breakChainForSaga(saga, gameId, eraNumber, eventId, outcomeId, paradoxId);
        }
    }

    private void breakChainForSaga(
            WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, UUID paradoxId) {
        var chain = chains.findById(saga.chainId());
        var pending = chain.pendingLink();
        if (pending == null
                || !pending.eventId().equals(eventId)
                || !pending.outcomeId().equals(outcomeId)) {
            return;
        }
        if (pending.eraNumber() != eraNumber) {
            if (pending.eraNumber() < eraNumber) {
                expirePendingLink(gameId, eraNumber, saga, chain);
            }
            return;
        }
        breakChain(saga, gameId, eraNumber, paradoxId, chain);
    }

    private void breakChain(WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID paradoxId, WeaverChain chain) {
        var chainLengthAtBreak = chain.length();
        var broken = chain.breakChain("CHAIN_CONFLICT paradox cascaded unresolved");
        chains.append(saga.chainId(), broken);
        sagas.save(new WeaverChainSagaState(
                saga.chainId(),
                gameId,
                saga.playerId(),
                WeaverChainSagaStatus.BROKEN,
                false,
                saga.tapestryUsedEra(),
                saga.reweaveUsedEra()));
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainBrokenEvent(gameId, eraNumber, saga.chainId(), saga.playerId(), paradoxId, chainLengthAtBreak),
                clock));
    }

    /** Confirms the given saga's chain's pending link, publishing {@code ChainLinkAdded} (+ {@code ChainCompleted}). */
    private void confirmPendingLink(UUID gameId, WeaverChainSagaState saga) {
        var chain = chains.findById(saga.chainId());
        var pending = chain.pendingLink();
        var beforeLength = chain.length();
        List<WeaverChainEvent> facts = chain.confirmPendingLink();
        chains.appendAll(saga.chainId(), facts);
        var grown = chains.findById(saga.chainId());
        UUID previousLinkEventId =
                beforeLength == 0 ? null : grown.links().get(beforeLength - 1).eventId();
        publisher.publish(TimelineEventEnvelope.create(
                saga.chainId(),
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ChainLinkAddedEvent(
                        gameId,
                        saga.chainId(),
                        saga.playerId(),
                        pending.eventId(),
                        pending.outcomeId(),
                        grown.length(),
                        previousLinkEventId),
                clock));
        if (facts.stream().anyMatch(ChainCompleted.class::isInstance)) {
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.COMPLETED,
                    false,
                    saga.tapestryUsedEra(),
                    saga.reweaveUsedEra()));
            publishChainCompleted(gameId, saga, grown);
        }
    }

    /** Clears the given saga's chain's pending link, publishing {@code ChainLinkInvalidated} (no penalty). */
    private void clearPendingLink(UUID gameId, int eraNumber, WeaverChainSagaState saga, WeaverChain chain) {
        var fact = chain.clearPendingLink();
        chains.append(saga.chainId(), fact);
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
                        fact.eventId(),
                        fact.outcomeId(),
                        current.length()),
                clock));
    }

    /** Expires a pending prediction and disarms protection armed in its era. */
    private void expirePendingLink(UUID gameId, int eraNumber, WeaverChainSagaState saga, WeaverChain chain) {
        var pendingEra = chain.pendingLink().eraNumber();
        clearPendingLink(gameId, eraNumber, saga, chain);
        if (saga.tapestryProtected() && Integer.valueOf(pendingEra).equals(saga.tapestryUsedEra())) {
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.OPEN,
                    false,
                    saga.tapestryUsedEra(),
                    saga.reweaveUsedEra()));
        }
    }

    @Override
    @Transactional
    public void endGame(UUID gameId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            if (chain.pendingLink() != null) {
                clearPendingLink(gameId, chain.pendingLink().eraNumber(), saga, chain);
            }
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
     * REWEAVE's target with the era it resolved in, present only when the outcome is the one its event drew.
     * Unknown events, unresolved events, and outcomes that lost the draw are all absent.
     */
    private Optional<ResolvedOutcome> resolvedTarget(OutcomeCoordinate target) {
        final FutureEvent futureEvent;
        try {
            futureEvent = futureEvents.findById(target.eventId());
        } catch (FutureEventNotFoundException _) {
            return Optional.empty();
        }
        return futureEvent
                .resolution()
                .filter(resolution -> resolution.winningOutcomeId().equals(target.outcomeId()))
                .map(resolution -> new ResolvedOutcome(target.eventId(), target.outcomeId(), resolution.eraNumber()));
    }

    private static List<ChainCompletedEvent.ChainLinkEntry> toEntries(List<ChainLink> links) {
        return links.stream()
                .map(link -> new ChainCompletedEvent.ChainLinkEntry(link.eventId(), link.outcomeId(), link.eraNumber()))
                .toList();
    }

    private void publishThreadRejected(
            UUID gameId, int eraNumber, UUID chainId, UUID playerId, OutcomeCoordinate coordinate, String reason) {
        publisher.publish(TimelineEventEnvelope.create(
                chainId == null ? playerId : chainId,
                AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new ThreadRejectedEvent(
                        gameId, eraNumber, chainId, playerId, coordinate.eventId(), coordinate.outcomeId(), reason),
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
