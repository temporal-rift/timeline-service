package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.application.port.in.SettleCascadeCarryForwardUseCase;
import io.github.temporalrift.timeline.application.port.in.WeaverChainSagaUseCase;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.DetectedParadox;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxDetector;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.futureevent.SimultaneousShift;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

/**
 * Coordinates timer-expiry and all-submitted closes through {@link #tryClose}; the persisted phase status makes the
 * losing race participant a no-op. Timer scheduling is composed outside this class to avoid a dependency cycle.
 */
@Component
class ParadoxResolutionSagaImpl {

    private static final Logger log = LoggerFactory.getLogger(ParadoxResolutionSagaImpl.class);

    private static final String ERA_AGGREGATE_TYPE = "Era";
    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";
    private static final String CLOSE_REASON_TIMER_EXPIRED = "TIMER_EXPIRED";
    private static final String CLOSE_REASON_ALL_SUBMITTED = "ALL_SUBMITTED";
    private static final String STABILIZE = "STABILIZE";

    private final ParadoxResolutionPhaseStateManager stateManager;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final EraPlayersPort eraPlayers;
    private final TimelineEventPublisher publisher;
    private final ParadoxResolutionRulesPort rules;
    private final ProbabilityRulesPort probabilityRules;
    private final WeaverChainSagaRepository chainSagas;
    private final WeaverChainRepository chains;
    private final WeaverChainSagaUseCase weaverChainSaga;
    private final SettleCascadeCarryForwardUseCase settleCascades;
    private final Clock clock;
    private final RandomGenerator random;

    ParadoxResolutionSagaImpl(
            ParadoxResolutionPhaseStateManager stateManager,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            EraPlayersPort eraPlayers,
            TimelineEventPublisher publisher,
            ParadoxResolutionRulesPort rules,
            ProbabilityRulesPort probabilityRules,
            WeaverChainSagaRepository chainSagas,
            WeaverChainRepository chains,
            WeaverChainSagaUseCase weaverChainSaga,
            SettleCascadeCarryForwardUseCase settleCascades,
            Clock clock,
            RandomGenerator random) {
        this.stateManager = stateManager;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.eraPlayers = eraPlayers;
        this.publisher = publisher;
        this.rules = rules;
        this.probabilityRules = probabilityRules;
        this.chainSagas = chainSagas;
        this.chains = chains;
        this.weaverChainSaga = weaverChainSaga;
        this.settleCascades = settleCascades;
        this.clock = clock;
        this.random = random;
    }

    /**
     * Creates the phase if none exists yet for this era (atomic — {@link ParadoxResolutionPhaseStateManager}); the
     * returned {@link OpenResult#phase()} is always authoritative for the era, whether this call created it or a
     * prior one did. {@code created()} is false when a redelivered/duplicate resolution attempt raced (or simply
     * followed) an already-open phase — the caller must not (re)announce {@code ParadoxResolutionPhaseStarted} or
     * (re)schedule a timer in that case, but still needs the authoritative {@code pendingParadoxes} (with their
     * real, already-published {@code paradoxId}s) to avoid announcing fresh ids nobody will ever cascade.
     */
    OpenResult openPhase(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions) {
        // Unlike ActionRoundSagaImpl.start(), this never checks for an already-empty pendingPlayerIds and
        // auto-closes: ActionRoundSaga's equivalent check exists because Activist players can arrive at
        // round-start already implicitly submitted (Rally/Momentum declarations). There is no equivalent
        // "already submitted" path here — a known-empty roster only ever means a misconfigured EraStarted,
        // never "nobody needs to submit," so the phase is left WAITING for its timer either way.
        var timerSeconds = rules.paradoxResolutionTimerSeconds();
        var timerExpiresAt = clock.instant().plusSeconds(timerSeconds);
        var sagaId = UUID.randomUUID();
        var candidate = eraPlayers
                .find(gameId, eraNumber)
                .map(playerIds -> ParadoxResolutionPhase.withKnownRoster(
                        sagaId,
                        gameId,
                        eraNumber,
                        ParadoxResolutionPhaseStatus.WAITING,
                        pendingParadoxes,
                        resolvedTerminalResolutions,
                        playerIds,
                        List.of(),
                        timerExpiresAt))
                .orElseGet(() -> ParadoxResolutionPhase.withUnknownRoster(
                        sagaId,
                        gameId,
                        eraNumber,
                        ParadoxResolutionPhaseStatus.WAITING,
                        pendingParadoxes,
                        resolvedTerminalResolutions,
                        List.of(),
                        timerExpiresAt));
        var result = stateManager.createIfAbsent(candidate);
        if (result.created()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxResolutionPhaseStarted(
                            gameId,
                            eraNumber,
                            result.phase().pendingParadoxes().stream()
                                    .map(PendingParadox::paradoxId)
                                    .toList(),
                            result.phase().pendingParadoxes().stream()
                                    .map(PendingParadox::affectedEventId)
                                    .distinct()
                                    .toList(),
                            timerSeconds),
                    clock));
        }
        return new OpenResult(result.phase(), result.created());
    }

    void handleTimerExpiry(UUID sagaId) {
        stateManager
                .findBySagaIdWithLock(sagaId)
                .ifPresentOrElse(
                        phase -> tryClose(phase, CLOSE_REASON_TIMER_EXPIRED),
                        () -> log.debug("handleTimerExpiry: phase {} not found (stale or duplicate fire)", sagaId));
    }

    /**
     * Records a player's resolution-card submission; when it leaves no player still pending, closes the phase
     * immediately by the same {@link #tryClose} the timer-expiry path uses. The returned phase from
     * {@code markSubmitted} already holds the row lock for the remainder of this transaction, so no
     * second fetch is needed. {@code markSubmitted} returns a phase only when it actually recorded the
     * submission, so a duplicate or non-roster player never reaches the close trigger.
     */
    void handlePlayerSubmitted(UUID gameId, int eraNumber, Submission submission) {
        stateManager
                .markSubmitted(gameId, eraNumber, submission)
                .filter(ParadoxResolutionPhase::allPlayersSubmitted)
                .ifPresent(phase -> tryClose(phase, CLOSE_REASON_ALL_SUBMITTED));
    }

    /**
     * Performs a resolution phase's entire close — status guard, applying submitted cards, re-detection,
     * publishing, marking {@code COMPLETED} — inside the transaction that holds {@code phase}'s row lock
     * (acquired by the caller). A phase not {@code WAITING} here is the loser of a race against the other close
     * trigger: it already lost the lock to whichever transaction closed the phase first, and on acquiring it now
     * sees that committed, terminal status, so there is nothing left to do.
     */
    private void tryClose(ParadoxResolutionPhase phase, String closeReason) {
        if (phase.status() != ParadoxResolutionPhaseStatus.WAITING) {
            log.debug("tryClose: phase {} already {} ({})", phase.sagaId(), phase.status(), closeReason);
            return;
        }

        applySubmissions(phase);
        var resolversByEvent = resolversByEvent(phase);

        var activeChains = loadActiveChains(phase.gameId());
        var terminalResolutions = new ArrayList<TerminalResolution>();
        for (var affectedEventId : distinctAffectedEventIds(phase)) {
            closeEvent(phase, affectedEventId, resolversByEvent, activeChains, terminalResolutions);
        }

        stateManager.complete(phase);

        var allTerminalResolutions = new ArrayList<>(phase.resolvedTerminalResolutions());
        allTerminalResolutions.addAll(terminalResolutions);
        allTerminalResolutions.sort(Comparator.comparingInt(TerminalResolution::revealIndex));
        settleCascades.settle(phase.gameId(), phase.eraNumber(), allTerminalResolutions);
        publisher.publish(TimelineEventEnvelope.create(
                phase.gameId(),
                ERA_AGGREGATE_TYPE,
                phase.gameId(),
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new EraResolutionCompleted(phase.gameId(), phase.eraNumber(), allTerminalResolutions),
                clock));
    }

    /**
     * Applies every recorded submission's {@code PUSH}/{@code SUPPRESS} effect to its target event, all of one
     * event's shifts simultaneously so submission order never changes its weights; anything else (an unsupported
     * card type, or a still-pending non-submitter — simply absent from {@code submissions}) is skipped.
     */
    private void applySubmissions(ParadoxResolutionPhase phase) {
        var shiftsByEvent = new LinkedHashMap<UUID, List<SimultaneousShift>>();
        for (var submission : phase.submissions()) {
            var shift = toProbabilityShift(submission);
            if (shift != null) {
                shiftsByEvent
                        .computeIfAbsent(submission.targetEventId(), _ -> new ArrayList<>())
                        .add(new SimultaneousShift(shift, magnitudeFor(submission)));
            }
        }
        shiftsByEvent.forEach(this::applyShifts);
    }

    /**
     * Each event's credited resolvers for {@code ParadoxResolved}: its STABILIZE submitters when any targeted it,
     * otherwise its PUSH/SUPPRESS submitters — distinct and sorted, so submission order never matters.
     */
    private static Map<UUID, List<UUID>> resolversByEvent(ParadoxResolutionPhase phase) {
        var stabilizersByEvent = new HashMap<UUID, SortedSet<UUID>>();
        var shiftersByEvent = new HashMap<UUID, SortedSet<UUID>>();
        for (var submission : phase.submissions()) {
            if (STABILIZE.equals(submission.cardType())) {
                stabilizersByEvent
                        .computeIfAbsent(submission.targetEventId(), _ -> new TreeSet<>())
                        .add(submission.playerId());
            } else if (toProbabilityShift(submission) != null) {
                shiftersByEvent
                        .computeIfAbsent(submission.targetEventId(), _ -> new TreeSet<>())
                        .add(submission.playerId());
            }
        }
        var resolvers = new HashMap<UUID, List<UUID>>();
        shiftersByEvent.forEach((eventId, players) -> resolvers.put(eventId, List.copyOf(players)));
        stabilizersByEvent.forEach((eventId, players) -> resolvers.put(eventId, List.copyOf(players)));
        return resolvers;
    }

    private void applyShifts(UUID eventId, List<SimultaneousShift> shifts) {
        var futureEvent = futureEvents.findById(eventId);
        var result = futureEvent.applySimultaneousShifts(
                shifts, probabilityRules.probabilityFloor(), probabilityRules.probabilityCeiling());
        result.events().forEach(event -> futureEvents.append(eventId, event));
    }

    private int magnitudeFor(Submission submission) {
        return switch (submission.cardType()) {
            case "PUSH" -> probabilityRules.pushShift(submission.grade());
            case "SUPPRESS" -> probabilityRules.suppressShift(submission.grade());
            default -> 0;
        };
    }

    private static ProbabilityShift toProbabilityShift(Submission submission) {
        return switch (submission.cardType()) {
            case "PUSH" -> new ProbabilityShift.Push(submission.targetOutcomeId());
            case "SUPPRESS" -> new ProbabilityShift.Suppress(submission.targetOutcomeId());
            default -> null;
        };
    }

    private static Set<UUID> distinctAffectedEventIds(ParadoxResolutionPhase phase) {
        var affectedEventIds = new LinkedHashSet<UUID>();
        phase.pendingParadoxes().forEach(pending -> affectedEventIds.add(pending.affectedEventId()));
        return affectedEventIds;
    }

    private List<WeaverChain> loadActiveChains(UUID gameId) {
        var activeChains = new ArrayList<WeaverChain>();
        for (var saga : chainSagas.findOpenByGame(gameId)) {
            var chain = chains.findById(saga.chainId());
            if (chain.status() == ChainStatus.ACTIVE && chain.gameId().equals(gameId)) {
                activeChains.add(chain);
            }
        }
        return List.copyOf(activeChains);
    }

    private void closeEvent(
            ParadoxResolutionPhase phase,
            UUID affectedEventId,
            Map<UUID, List<UUID>> resolversByEvent,
            List<WeaverChain> activeChains,
            List<TerminalResolution> terminalResolutions) {
        var eventPendingParadoxes = phase.pendingParadoxes().stream()
                .filter(pending -> pending.affectedEventId().equals(affectedEventId))
                .toList();
        var revealIndex = eventPendingParadoxes.getFirst().revealIndex();
        var futureEvent = futureEvents.findById(affectedEventId);

        boolean stabilized = isStabilized(phase, affectedEventId, futureEvent);
        var freshParadoxes = stabilized
                ? List.<DetectedParadox>of()
                : ParadoxDetector.detect(
                        futureEvent.outcomes(), futureEvent.collidedPairs(), futureEvent.id(), activeChains);

        var persistingIds = reconcileFindings(
                phase,
                affectedEventId,
                eventPendingParadoxes,
                freshParadoxes,
                resolversByEvent.getOrDefault(affectedEventId, List.of()));

        if (persistingIds.isEmpty()) {
            var outcomeApplied = futureEvent.resolve(phase.gameId(), phase.eraNumber(), random.nextLong());
            futureEvents.append(affectedEventId, outcomeApplied);
            weaverChainSaga.resolvePendingLink(
                    phase.gameId(), phase.eraNumber(), affectedEventId, outcomeApplied.winningOutcomeId());
            publisher.publish(TimelineEventEnvelope.create(
                    affectedEventId,
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    phase.gameId(),
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    outcomeApplied,
                    clock));
            terminalResolutions.add(new TerminalResolution(
                    affectedEventId,
                    revealIndex,
                    TerminalResolution.TerminalState.OUTCOME_APPLIED,
                    outcomeApplied.winningOutcomeId()));
        } else {
            publisher.publish(TimelineEventEnvelope.create(
                    affectedEventId,
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    phase.gameId(),
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxCascaded(
                            phase.gameId(),
                            phase.eraNumber(),
                            persistingIds.getFirst(),
                            persistingIds,
                            affectedEventId,
                            futureEvent.outcomes(),
                            detonatedByPlayerIds(phase, affectedEventId)),
                    clock));
            eraIndex.add(affectedEventId, phase.gameId(), phase.eraNumber() + 1, revealIndex);
            terminalResolutions.add(new TerminalResolution(
                    affectedEventId, revealIndex, TerminalResolution.TerminalState.CASCADED, null));
        }
    }

    /** Reconciles findings one-to-one so each current finding has one stable or newly announced id. */
    private List<UUID> reconcileFindings(
            ParadoxResolutionPhase phase,
            UUID affectedEventId,
            List<PendingParadox> pendingParadoxes,
            List<DetectedParadox> freshParadoxes,
            List<UUID> resolvedByPlayerIds) {
        var unmatchedFresh = new ArrayList<>(freshParadoxes);
        var persistingIds = new ArrayList<UUID>();
        for (var pending : pendingParadoxes) {
            var matchingFresh = unmatchedFresh.stream()
                    .filter(fresh -> sameFinding(pending, fresh))
                    .findFirst();
            if (matchingFresh.isPresent()) {
                unmatchedFresh.remove(matchingFresh.get());
                persistingIds.add(pending.paradoxId());
                breakChainOnCascadeIfChainConflict(
                        phase,
                        affectedEventId,
                        pending.type(),
                        pending.affectedOutcomeIds().getFirst(),
                        pending.paradoxId());
            } else {
                publisher.publish(TimelineEventEnvelope.create(
                        affectedEventId,
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        phase.gameId(),
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ParadoxResolved(
                                phase.gameId(), phase.eraNumber(), pending.paradoxId(), resolvedByPlayerIds),
                        clock));
                if (pending.type() == ParadoxType.CHAIN_CONFLICT) {
                    weaverChainSaga.confirmParadoxResolvedLink(
                            phase.gameId(),
                            phase.eraNumber(),
                            affectedEventId,
                            pending.affectedOutcomeIds().getFirst());
                }
            }
        }

        if (!unmatchedFresh.isEmpty()) {
            var newFindings = new ArrayList<ParadoxDetected.Paradox>();
            for (var fresh : unmatchedFresh) {
                var paradoxId = UUID.randomUUID();
                persistingIds.add(paradoxId);
                newFindings.add(new ParadoxDetected.Paradox(
                        paradoxId, fresh.type(), affectedEventId, fresh.affectedOutcomeIds(), fresh.description()));
                breakChainOnCascadeIfChainConflict(
                        phase,
                        affectedEventId,
                        fresh.type(),
                        fresh.affectedOutcomeIds().getFirst(),
                        paradoxId);
            }
            publisher.publish(TimelineEventEnvelope.create(
                    phase.gameId(),
                    ERA_AGGREGATE_TYPE,
                    phase.gameId(),
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxDetected(phase.gameId(), phase.eraNumber(), newFindings),
                    clock));
        }
        return List.copyOf(persistingIds);
    }

    /** Breaks the weaver chain only for {@code CHAIN_CONFLICT} findings carried into the next era. */
    private void breakChainOnCascadeIfChainConflict(
            ParadoxResolutionPhase phase,
            UUID affectedEventId,
            ParadoxType type,
            UUID affectedOutcomeId,
            UUID paradoxId) {
        if (type == ParadoxType.CHAIN_CONFLICT) {
            weaverChainSaga.breakChainOnCascadedParadox(
                    phase.gameId(), phase.eraNumber(), affectedEventId, affectedOutcomeId, paradoxId);
        }
    }

    /**
     * STABILIZE clears every finding without changing weights, so a remaining tie is settled by the ordinary weighted
     * draw; it has no effect on an event with no drawable weight left (see {@code IMPOSSIBLE_ERASURE}).
     */
    private static boolean isStabilized(ParadoxResolutionPhase phase, UUID affectedEventId, FutureEvent futureEvent) {
        return futureEvent.hasDrawableWeight()
                && phase.submissions().stream()
                        .anyMatch(s -> STABILIZE.equals(s.cardType()) && affectedEventId.equals(s.targetEventId()));
    }

    /**
     * Every distinct player who submitted {@code DETONATE} targeting {@code affectedEventId} this close —
     * deduplicated (multiple {@code DETONATE}s on the same event collapse into one set because the effect does
     * not stack) and only meaningful when the event actually cascades; a {@code STABILIZE}-cleared event never
     * gets a {@code ParadoxCascaded} published for it at all, so this set is simply never read in that case.
     */
    private static List<UUID> detonatedByPlayerIds(ParadoxResolutionPhase phase, UUID affectedEventId) {
        return phase.submissions().stream()
                .filter(s -> "DETONATE".equals(s.cardType()) && affectedEventId.equals(s.targetEventId()))
                .map(Submission::playerId)
                .distinct()
                .toList();
    }

    /**
     * A finding "persists" only when a fresh detection reports the same type against the same set of affected
     * outcome ids — comparing {@code type} alone would conflate two distinct same-type findings on one event.
     */
    private static boolean sameFinding(PendingParadox pending, DetectedParadox fresh) {
        var pendingOutcomeIds = Set.copyOf(pending.affectedOutcomeIds());
        return fresh.type() == pending.type()
                && Set.copyOf(fresh.affectedOutcomeIds()).equals(pendingOutcomeIds);
    }

    record OpenResult(ParadoxResolutionPhase phase, boolean created) {}
}
