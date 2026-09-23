package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
     * sees that committed, terminal status, so there is nothing left to do (the governing design Decision 2).
     */
    private void tryClose(ParadoxResolutionPhase phase, String closeReason) {
        if (phase.status() != ParadoxResolutionPhaseStatus.WAITING) {
            log.debug("tryClose: phase {} already {} ({})", phase.sagaId(), phase.status(), closeReason);
            return;
        }

        var resolvedByPlayerIdByEvent = applySubmissions(phase);

        var activeChains = loadActiveChains(phase.gameId());
        var terminalResolutions = new ArrayList<TerminalResolution>();
        for (var affectedEventId : distinctAffectedEventIds(phase)) {
            closeEvent(phase, affectedEventId, resolvedByPlayerIdByEvent, activeChains, terminalResolutions);
        }

        stateManager.complete(phase);

        var allTerminalResolutions = new ArrayList<>(phase.resolvedTerminalResolutions());
        allTerminalResolutions.addAll(terminalResolutions);
        allTerminalResolutions.sort(Comparator.comparingInt(TerminalResolution::revealIndex));
        publisher.publish(TimelineEventEnvelope.create(
                phase.gameId(),
                ERA_AGGREGATE_TYPE,
                phase.gameId(),
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new EraResolutionCompleted(phase.gameId(), phase.eraNumber(), allTerminalResolutions),
                clock));
    }

    /**
     * Applies every recorded submission's {@code PUSH}/{@code SUPPRESS} effect to its target event (Decision 4);
     * anything else (an unsupported card type, or a still-pending non-submitter — simply absent from
     * {@code submissions}) is skipped. Returns the last submitting player per affected event, in submission-record
     * order, for {@code ParadoxResolved.resolvedByPlayerId}.
     */
    private Map<UUID, UUID> applySubmissions(ParadoxResolutionPhase phase) {
        var resolvedByPlayerIdByEvent = new HashMap<UUID, UUID>();
        for (var submission : phase.submissions()) {
            var shift = toProbabilityShift(submission);
            if (shift == null) {
                continue;
            }
            var futureEvent = futureEvents.findById(submission.targetEventId());
            var event = futureEvent.applyShift(
                    shift,
                    magnitudeFor(submission),
                    probabilityRules.probabilityFloor(),
                    probabilityRules.probabilityCeiling());
            futureEvents.append(submission.targetEventId(), event);
            resolvedByPlayerIdByEvent.put(submission.targetEventId(), submission.playerId());
        }
        return resolvedByPlayerIdByEvent;
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
            Map<UUID, UUID> resolvedByPlayerIdByEvent,
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
                        futureEvent.outcomes(), futureEvent.sealBreach(), futureEvent.id(), activeChains);

        var persistingIds = reconcileFindings(
                phase,
                affectedEventId,
                eventPendingParadoxes,
                freshParadoxes,
                resolvedByPlayerIdByEvent.get(affectedEventId));

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
            UUID resolvedByPlayerId) {
        var unmatchedFresh = new ArrayList<>(freshParadoxes);
        var persistingIds = new ArrayList<UUID>();
        for (var pending : pendingParadoxes) {
            var matchingFresh = unmatchedFresh.stream()
                    .filter(fresh -> sameFinding(pending, fresh))
                    .findFirst();
            if (matchingFresh.isPresent()) {
                unmatchedFresh.remove(matchingFresh.get());
                persistingIds.add(pending.paradoxId());
                if (pending.type() == ParadoxType.CHAIN_CONFLICT) {
                    weaverChainSaga.breakChainOnCascadedParadox(
                            phase.gameId(),
                            phase.eraNumber(),
                            affectedEventId,
                            pending.affectedOutcomeIds().getFirst(),
                            pending.paradoxId());
                }
            } else {
                publisher.publish(TimelineEventEnvelope.create(
                        affectedEventId,
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        phase.gameId(),
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ParadoxResolved(phase.gameId(), phase.eraNumber(), pending.paradoxId(), resolvedByPlayerId),
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
                if (fresh.type() == ParadoxType.CHAIN_CONFLICT) {
                    weaverChainSaga.breakChainOnCascadedParadox(
                            phase.gameId(),
                            phase.eraNumber(),
                            affectedEventId,
                            fresh.affectedOutcomeIds().getFirst(),
                            paradoxId);
                }
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

    /** STABILIZE suppresses re-detection except against an event with no eligible outcome left to draw. */
    private static boolean isStabilized(ParadoxResolutionPhase phase, UUID affectedEventId, FutureEvent futureEvent) {
        return futureEvent.hasEligibleOutcome()
                && phase.submissions().stream()
                        .anyMatch(s -> "STABILIZE".equals(s.cardType()) && affectedEventId.equals(s.targetEventId()));
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
     * outcome ids — comparing {@code type} alone would conflate two distinct same-type findings on one event
     * (Decision 2 / review finding).
     */
    private static boolean sameFinding(PendingParadox pending, DetectedParadox fresh) {
        var pendingOutcomeIds = Set.copyOf(pending.affectedOutcomeIds());
        return fresh.type() == pending.type()
                && Set.copyOf(fresh.affectedOutcomeIds()).equals(pendingOutcomeIds);
    }

    record OpenResult(ParadoxResolutionPhase phase, boolean created) {}
}
