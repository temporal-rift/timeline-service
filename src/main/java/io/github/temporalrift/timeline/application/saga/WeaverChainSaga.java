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

    private static final String REASON_MISSING_COORDINATE = "MISSING_COORDINATE";
    private static final String REASON_INVALID_COORDINATE = "INVALID_COORDINATE";
    private static final String REASON_ALREADY_PENDING = "ALREADY_PENDING";
    private static final String REASON_ALREADY_LINKED = "EVENT_ALREADY_LINKED";

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
    public void playThread(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        var saga = sagas.findOpenByGameAndPlayer(gameId, playerId).orElse(null);
        var coordinate = new OutcomeCoordinate(eventId, outcomeId);
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
        } catch (InvalidChainLinkException _) {
            var reason = chain.pendingLink() != null ? REASON_ALREADY_PENDING : REASON_ALREADY_LINKED;
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
            protectPendingLinkIfArmed(saga, gameId, eraNumber, targetEventId, targetOutcomeId);
        }
    }

    /**
     * When the chain's open pending link names the just-annihilated outcome: if Tapestry protection is armed
     * for this era, consumes it and confirms the link anyway; otherwise leaves the pending link as-is —
     * {@code ParadoxDetector} picks up the annihilated-and-unprotected pending link at era resolution.
     */
    private void protectPendingLinkIfArmed(
            WeaverChainSagaState saga, UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        var chain = chains.findById(saga.chainId());
        var pending = chain.pendingLink();
        if (pending == null
                || !pending.eventId().equals(eventId)
                || !pending.outcomeId().equals(outcomeId)) {
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
        confirmPendingLink(gameId, eraNumber, saga);
    }

    @Override
    @Transactional
    public void resolvePendingLink(UUID gameId, int eraNumber, UUID eventId, UUID winningOutcomeId) {
        for (var saga : sagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            var pending = chain.pendingLink();
            if (pending == null || !pending.eventId().equals(eventId)) {
                continue;
            }
            if (pending.eraNumber() != eraNumber) {
                if (pending.eraNumber() < eraNumber) {
                    expirePendingLink(gameId, eraNumber, saga, chain);
                }
                continue;
            }
            if (pending.outcomeId().equals(winningOutcomeId)) {
                confirmPendingLink(gameId, eraNumber, saga);
            } else {
                clearPendingLink(gameId, eraNumber, saga, chain);
            }
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
                    confirmPendingLink(gameId, eraNumber, saga);
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
            var chain = chains.findById(saga.chainId());
            var pending = chain.pendingLink();
            if (pending == null
                    || !pending.eventId().equals(eventId)
                    || !pending.outcomeId().equals(outcomeId)) {
                continue;
            }
            if (pending.eraNumber() != eraNumber) {
                if (pending.eraNumber() < eraNumber) {
                    expirePendingLink(gameId, eraNumber, saga, chain);
                }
                continue;
            }
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
                    new ChainBrokenEvent(
                            gameId, eraNumber, saga.chainId(), saga.playerId(), paradoxId, chainLengthAtBreak),
                    clock));
        }
    }

    /** Confirms the given saga's chain's pending link, publishing {@code ChainLinkAdded} (+ {@code ChainCompleted}). */
    private void confirmPendingLink(UUID gameId, int eraNumber, WeaverChainSagaState saga) {
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
            publisher.publish(TimelineEventEnvelope.create(
                    saga.chainId(),
                    AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ChainCompletedEvent(
                            gameId, eraNumber, saga.chainId(), saga.playerId(), toEntries(grown.links())),
                    clock));
            sagas.save(new WeaverChainSagaState(
                    saga.chainId(),
                    gameId,
                    saga.playerId(),
                    WeaverChainSagaStatus.COMPLETED,
                    false,
                    saga.tapestryUsedEra(),
                    saga.reweaveUsedEra()));
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

    /** Expires a pending prediction and disarms its protection. */
    private void expirePendingLink(UUID gameId, int eraNumber, WeaverChainSagaState saga, WeaverChain chain) {
        clearPendingLink(gameId, eraNumber, saga, chain);
        if (saga.tapestryProtected()) {
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
     * Whether the referenced outcome actually resolved as the winner of its event — the causal-link
     * validity rule for REWEAVE's target. Unknown events, unresolved events, and resolved-differently
     * outcomes all fail.
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
        return Objects.equals(winningOutcomeId(futureEvent), outcomeId);
    }

    /** Highest-probability non-annihilated outcome, ties broken by smallest outcomeId — as resolved. */
    private static UUID winningOutcomeId(FutureEvent futureEvent) {
        return futureEvent.outcomes().stream()
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
