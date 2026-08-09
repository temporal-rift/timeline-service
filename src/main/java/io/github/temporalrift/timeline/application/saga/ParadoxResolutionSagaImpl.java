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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.DetectedParadox;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxDetector;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

/**
 * Business logic for {@code sagas.md} Saga 5: both the force-cascade (timer expiry) and player-submission
 * (all-submitted) close branches, sharing one {@link #tryClose} (design.md timeline-mvp8-paradox-completion
 * Decision 2). Holds no direct dependency on timer scheduling: {@link ParadoxResolutionPhaseOpener} and
 * {@link ParadoxResolutionTimeoutProcessor} compose this class with {@link ParadoxResolutionTimerScheduler}, which
 * avoids a circular dependency (scheduler -> timeout processor -> this class).
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
    private final Clock clock;

    ParadoxResolutionSagaImpl(
            ParadoxResolutionPhaseStateManager stateManager,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            EraPlayersPort eraPlayers,
            TimelineEventPublisher publisher,
            ParadoxResolutionRulesPort rules,
            ProbabilityRulesPort probabilityRules,
            Clock clock) {
        this.stateManager = stateManager;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.eraPlayers = eraPlayers;
        this.publisher = publisher;
        this.rules = rules;
        this.probabilityRules = probabilityRules;
        this.clock = clock;
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
        // "already submitted" path here — an empty roster only ever means a missing/misconfigured EraStarted,
        // never "nobody needs to submit," so the phase is left WAITING for its timer either way.
        var timerSeconds = rules.paradoxResolutionTimerSeconds();
        var timerExpiresAt = clock.instant().plusSeconds(timerSeconds);
        var candidate = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                gameId,
                eraNumber,
                ParadoxResolutionPhaseStatus.WAITING,
                pendingParadoxes,
                resolvedTerminalResolutions,
                eraPlayers.find(gameId, eraNumber),
                List.of(),
                timerExpiresAt);
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
     * immediately by the same {@link #tryClose} the timer-expiry path uses (design.md Decision 2) — the returned
     * phase from {@code markSubmitted} already holds the row lock for the remainder of this transaction, so no
     * second fetch is needed.
     */
    void handlePlayerSubmitted(UUID gameId, int eraNumber, Submission submission) {
        stateManager
                .markSubmitted(gameId, eraNumber, submission)
                .filter(phase -> phase.pendingPlayerIds().isEmpty())
                .ifPresent(phase -> tryClose(phase, CLOSE_REASON_ALL_SUBMITTED));
    }

    /**
     * Performs a resolution phase's entire close — status guard, applying submitted cards, re-detection,
     * publishing, marking {@code COMPLETED} — inside the transaction that holds {@code phase}'s row lock
     * (acquired by the caller). A phase not {@code WAITING} here is the loser of a race against the other close
     * trigger: it already lost the lock to whichever transaction closed the phase first, and on acquiring it now
     * sees that committed, terminal status, so there is nothing left to do (design.md Decision 2).
     */
    private void tryClose(ParadoxResolutionPhase phase, String closeReason) {
        if (phase.status() != ParadoxResolutionPhaseStatus.WAITING) {
            log.debug("tryClose: phase {} already {} ({})", phase.sagaId(), phase.status(), closeReason);
            return;
        }

        var resolvedByPlayerIdByEvent = applySubmissions(phase);

        var terminalResolutions = new ArrayList<TerminalResolution>();
        for (var affectedEventId : distinctAffectedEventIds(phase)) {
            closeEvent(phase, affectedEventId, resolvedByPlayerIdByEvent, terminalResolutions);
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
            case "PUSH" -> probabilityRules.pushShift();
            case "SUPPRESS" -> probabilityRules.suppressShift();
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

    /**
     * Re-runs detection on one affected event's current state; for each of its originally detected paradoxes,
     * publishes {@code ParadoxResolved} when that type is no longer present or {@code ParadoxCascaded} when it
     * still is. The event resolves normally (Decision 5) only when every one of its original paradoxes cleared;
     * otherwise the whole event cascades exactly once even if it had multiple paradoxes.
     */
    private void closeEvent(
            ParadoxResolutionPhase phase,
            UUID affectedEventId,
            Map<UUID, UUID> resolvedByPlayerIdByEvent,
            List<TerminalResolution> terminalResolutions) {
        var eventPendingParadoxes = phase.pendingParadoxes().stream()
                .filter(pending -> pending.affectedEventId().equals(affectedEventId))
                .toList();
        var revealIndex = eventPendingParadoxes.getFirst().revealIndex();
        var futureEvent = futureEvents.findById(affectedEventId);
        var freshTypes = ParadoxDetector.detect(futureEvent.outcomes(), futureEvent.sealBreach()).stream()
                .map(DetectedParadox::type)
                .collect(Collectors.toSet());

        var allResolved = true;
        for (var pending : eventPendingParadoxes) {
            if (freshTypes.contains(pending.type())) {
                allResolved = false;
                publisher.publish(TimelineEventEnvelope.create(
                        affectedEventId,
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        phase.gameId(),
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ParadoxCascaded(
                                phase.gameId(),
                                phase.eraNumber(),
                                pending.paradoxId(),
                                affectedEventId,
                                futureEvent.outcomes()),
                        clock));
            } else {
                publisher.publish(TimelineEventEnvelope.create(
                        affectedEventId,
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        phase.gameId(),
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        new ParadoxResolved(
                                phase.gameId(),
                                phase.eraNumber(),
                                pending.paradoxId(),
                                resolvedByPlayerIdByEvent.get(affectedEventId)),
                        clock));
            }
        }

        if (allResolved) {
            var outcomeApplied = futureEvent.resolve(phase.gameId(), phase.eraNumber());
            futureEvents.append(affectedEventId, outcomeApplied);
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
            eraIndex.add(affectedEventId, phase.gameId(), phase.eraNumber() + 1, revealIndex);
            terminalResolutions.add(new TerminalResolution(
                    affectedEventId, revealIndex, TerminalResolution.TerminalState.CASCADED, null));
        }
    }

    record OpenResult(ParadoxResolutionPhase phase, boolean created) {}
}
