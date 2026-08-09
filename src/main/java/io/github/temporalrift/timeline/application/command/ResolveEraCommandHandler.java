package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.OpenParadoxResolutionPhaseUseCase;
import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxDetector;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;

/**
 * Resolves every {@code FutureEvent} drawn for an era: highest-probability wins, deterministic
 * {@code outcomeId} tie-break, no faction-special logic beyond the card-modifier effects
 * (AMPLIFY/NULLIFY/REDIRECT/STALL) already reflected in each {@code FutureEvent}'s current state. An event whose
 * final state trips {@link ParadoxDetector} is excluded from this era's {@code OutcomeApplied}/terminal-resolution
 * set instead (design.md) — its resolution is deferred until {@code ParadoxResolutionSaga} clears it (force-cascade
 * only this slice). Emits one era-level {@code ProbabilityStateCalculated} before any {@code OutcomeApplied}
 * (design.md requirement: emission order). When any paradox is detected this cycle, {@code EraResolutionCompleted}
 * is not published here — {@link OpenParadoxResolutionPhaseUseCase} opens a resolution phase instead, and the
 * barrier is published once that phase's paradoxes all reach a terminal state (timeline-mvp7-paradox-resolution-saga
 * design.md).
 */
@Service
class ResolveEraCommandHandler implements ResolveEraUseCase {

    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";
    private static final String ERA_AGGREGATE_TYPE = "Era";

    private final FutureEventEraIndexPort eraIndex;
    private final FutureEventRepository futureEvents;
    private final TimelineEventPublisher publisher;
    private final OpenParadoxResolutionPhaseUseCase openParadoxResolutionPhase;
    private final Clock clock;

    ResolveEraCommandHandler(
            FutureEventEraIndexPort eraIndex,
            FutureEventRepository futureEvents,
            TimelineEventPublisher publisher,
            OpenParadoxResolutionPhaseUseCase openParadoxResolutionPhase,
            Clock clock) {
        this.eraIndex = eraIndex;
        this.futureEvents = futureEvents;
        this.publisher = publisher;
        this.openParadoxResolutionPhase = openParadoxResolutionPhase;
        this.clock = clock;
    }

    @Override
    public void resolve(UUID gameId, int eraNumber) {
        var indexedEventIds = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber);
        // A stalled event already carried into eraNumber + 1 by a prior call was already reported in that
        // call's EraResolutionCompleted — re-checking here keeps this idempotent without a one-way "resolved"
        // flag for STALLED the way OUTCOME_APPLIED has FutureEvent.resolved().
        var alreadyCarriedForward = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber + 1).stream()
                .map(IndexedEventId::eventId)
                .collect(Collectors.toSet());

        var accumulator = new ResolutionAccumulator();
        for (var indexedEventId : indexedEventIds) {
            // Already carried into eraNumber + 1 and reported as STALLED by a prior call for this era.
            // addStalled clears stalled() as part of carrying an event forward, so a redelivered call
            // reaching this event again would otherwise fall through to resolving it a second time.
            if (!alreadyCarriedForward.contains(indexedEventId.eventId())) {
                var futureEvent = futureEvents.findById(indexedEventId.eventId());
                resolveEvent(gameId, eraNumber, indexedEventId, futureEvent, accumulator);
            }
        }
        publishResolution(gameId, eraNumber, accumulator);
    }

    private void resolveEvent(
            UUID gameId,
            int eraNumber,
            IndexedEventId indexedEventId,
            FutureEvent futureEvent,
            ResolutionAccumulator accumulator) {
        if (futureEvent.resolved()) {
            // already resolved in a prior call for this era — nothing to do
            return;
        }
        if (futureEvent.stalled()) {
            addStalled(gameId, eraNumber, indexedEventId, futureEvent, accumulator.terminalResolutions());
            return;
        }
        var detected = ParadoxDetector.detect(futureEvent.outcomes(), futureEvent.sealBreach());
        if (detected.isEmpty()) {
            var outcomeApplied = resolveOne(futureEvent, gameId, eraNumber);
            accumulator.resolutions().add(outcomeApplied);
            accumulator
                    .terminalResolutions()
                    .add(new TerminalResolution(
                            outcomeApplied.eventId(),
                            indexedEventId.revealIndex(),
                            TerminalResolution.TerminalState.OUTCOME_APPLIED,
                            outcomeApplied.winningOutcomeId()));
        } else {
            // Left neither resolved() nor stalled(): a future resolution attempt for this same
            // gameId/eraNumber (paradox-resolution capability) re-evaluates it from scratch instead
            // of skipping it as already-handled. Each finding's paradoxId is shared between the
            // ParadoxDetected fact and the ParadoxResolutionSaga's pending-paradox entry so the saga's
            // later ParadoxCascaded references the same paradoxId this cycle announced.
            detected.forEach(d -> {
                var paradoxId = UUID.randomUUID();
                accumulator
                        .paradoxes()
                        .add(new ParadoxDetected.Paradox(
                                paradoxId, d.type(), futureEvent.id(), d.affectedOutcomeIds(), d.description()));
                accumulator
                        .pendingParadoxes()
                        .add(new PendingParadox(paradoxId, d.type(), futureEvent.id(), indexedEventId.revealIndex()));
            });
        }
    }

    private void publishResolution(UUID gameId, int eraNumber, ResolutionAccumulator accumulator) {
        if (accumulator.terminalResolutions().isEmpty()
                && accumulator.paradoxes().isEmpty()) {
            return;
        }
        var paradoxes = accumulator.paradoxes();
        if (!accumulator.pendingParadoxes().isEmpty()) {
            // Deferred: the barrier now waits for ParadoxResolutionSaga to force-cascade every paradox
            // detected this cycle before EraResolutionCompleted can be published (resolution-walking-skeleton
            // MODIFIED requirement, timeline-mvp7-paradox-resolution-saga). Opened before ParadoxDetected is
            // published, and its authoritative pendingParadoxes (not our own freshly-generated ids) are what
            // gets announced: if a phase already existed for this era (a duplicate/redelivered resolution
            // attempt), our ids belong to nothing the saga will ever cascade — only the existing phase's ids
            // ever get a ParadoxCascaded.
            var authoritativePendingParadoxes = openParadoxResolutionPhase.open(
                    gameId, eraNumber, accumulator.pendingParadoxes(), accumulator.terminalResolutions());
            paradoxes = reconcileParadoxIds(accumulator.paradoxes(), authoritativePendingParadoxes);
        }
        if (!accumulator.resolutions().isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    toProbabilityStateCalculated(gameId, eraNumber, accumulator.resolutions()),
                    clock));
        }
        if (!paradoxes.isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxDetected(gameId, eraNumber, paradoxes),
                    clock));
        }
        for (var outcomeApplied : accumulator.resolutions()) {
            publisher.publish(TimelineEventEnvelope.create(
                    outcomeApplied.eventId(),
                    FUTURE_EVENT_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    outcomeApplied,
                    clock));
        }
        if (accumulator.pendingParadoxes().isEmpty()
                && !accumulator.terminalResolutions().isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new EraResolutionCompleted(gameId, eraNumber, accumulator.terminalResolutions()),
                    clock));
        }
    }

    /**
     * Rebuilds each candidate paradox with its authoritative {@code paradoxId} in place of the provisional one
     * generated during detection. Matches positionally: {@code candidates} and {@code authoritativePendingParadoxes}
     * are both built/returned in the same detection order, which is deterministic for a given era's active events
     * (nothing else mutates a paradoxed FutureEvent's state between two resolution attempts for the same era in
     * this slice — no player-submission path exists yet), so index-based pairing is safe even when the
     * authoritative list belongs to an earlier call's already-persisted phase.
     */
    private static List<ParadoxDetected.Paradox> reconcileParadoxIds(
            List<ParadoxDetected.Paradox> candidates, List<PendingParadox> authoritativePendingParadoxes) {
        var reconciled = new ArrayList<ParadoxDetected.Paradox>(candidates.size());
        for (var i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            reconciled.add(new ParadoxDetected.Paradox(
                    authoritativePendingParadoxes.get(i).paradoxId(),
                    candidate.type(),
                    candidate.affectedEventId(),
                    candidate.affectedOutcomeIds(),
                    candidate.description()));
        }
        return reconciled;
    }

    /** Mutable per-call collector, populated by {@link #resolveEvent} and drained by {@link #publishResolution}. */
    private record ResolutionAccumulator(
            List<OutcomeApplied> resolutions,
            List<TerminalResolution> terminalResolutions,
            List<ParadoxDetected.Paradox> paradoxes,
            List<PendingParadox> pendingParadoxes) {

        ResolutionAccumulator() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private void addStalled(
            UUID gameId,
            int eraNumber,
            IndexedEventId indexedEventId,
            FutureEvent futureEvent,
            List<TerminalResolution> newTerminalResolutions) {
        eraIndex.add(futureEvent.id(), gameId, eraNumber + 1, indexedEventId.revealIndex());
        // The one-era delay is now spent: clear the flag so the carried event resolves normally next era
        // unless a fresh STALL is played on it there — otherwise it would carry forward indefinitely.
        futureEvents.append(futureEvent.id(), futureEvent.clearStalled());
        newTerminalResolutions.add(new TerminalResolution(
                futureEvent.id(), indexedEventId.revealIndex(), TerminalResolution.TerminalState.STALLED, null));
    }

    private OutcomeApplied resolveOne(FutureEvent futureEvent, UUID gameId, int eraNumber) {
        var outcomeApplied = futureEvent.resolve(gameId, eraNumber);
        futureEvents.append(futureEvent.id(), outcomeApplied);
        return outcomeApplied;
    }

    private static ProbabilityStateCalculated toProbabilityStateCalculated(
            UUID gameId, int eraNumber, List<OutcomeApplied> resolutions) {
        var eventStates = resolutions.stream()
                .map(r -> new ProbabilityStateCalculated.EventState(
                        r.eventId(),
                        r.finalOutcomes().stream()
                                .map(o -> new ProbabilityStateCalculated.OutcomeState(
                                        o.outcomeId(), o.probability(), o.annihilated(), o.sealed()))
                                .toList()))
                .toList();
        return new ProbabilityStateCalculated(gameId, eraNumber, eventStates);
    }
}
