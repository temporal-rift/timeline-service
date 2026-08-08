package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

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

/**
 * Resolves every {@code FutureEvent} drawn for an era: highest-probability wins, deterministic
 * {@code outcomeId} tie-break, no faction-special logic beyond the card-modifier effects
 * (AMPLIFY/NULLIFY/REDIRECT/STALL) already reflected in each {@code FutureEvent}'s current state. An event whose
 * final state trips {@link ParadoxDetector} is excluded from this era's {@code OutcomeApplied}/terminal-resolution
 * set instead (design.md) — its resolution is deferred until a future paradox-resolution capability clears it.
 * Emits one era-level {@code ProbabilityStateCalculated} before any {@code OutcomeApplied} (design.md requirement:
 * emission order).
 */
@Service
class ResolveEraCommandHandler implements ResolveEraUseCase {

    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";
    private static final String ERA_AGGREGATE_TYPE = "Era";

    private final FutureEventEraIndexPort eraIndex;
    private final FutureEventRepository futureEvents;
    private final TimelineEventPublisher publisher;
    private final Clock clock;

    ResolveEraCommandHandler(
            FutureEventEraIndexPort eraIndex,
            FutureEventRepository futureEvents,
            TimelineEventPublisher publisher,
            Clock clock) {
        this.eraIndex = eraIndex;
        this.futureEvents = futureEvents;
        this.publisher = publisher;
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
        var detected = ParadoxDetector.detect(futureEvent.outcomes());
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
            // of skipping it as already-handled.
            detected.forEach(d -> accumulator
                    .paradoxes()
                    .add(new ParadoxDetected.Paradox(
                            UUID.randomUUID(), d.type(), futureEvent.id(), d.affectedOutcomeIds(), d.description())));
        }
    }

    private void publishResolution(UUID gameId, int eraNumber, ResolutionAccumulator accumulator) {
        if (accumulator.terminalResolutions().isEmpty()
                && accumulator.paradoxes().isEmpty()) {
            return;
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
        if (!accumulator.paradoxes().isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new ParadoxDetected(gameId, eraNumber, accumulator.paradoxes()),
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
        if (!accumulator.terminalResolutions().isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    new EraResolutionCompleted(gameId, eraNumber, accumulator.terminalResolutions()),
                    clock));
        }
    }

    /** Mutable per-call collector, populated by {@link #resolveEvent} and drained by {@link #publishResolution}. */
    private record ResolutionAccumulator(
            List<OutcomeApplied> resolutions,
            List<TerminalResolution> terminalResolutions,
            List<ParadoxDetected.Paradox> paradoxes) {

        ResolutionAccumulator() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
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
