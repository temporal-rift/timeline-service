package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Resolves every {@code FutureEvent} drawn for an era: highest-probability wins, deterministic
 * {@code outcomeId} tie-break, no faction-special or paradox logic (design.md Decision 3) beyond the
 * card-modifier effects (AMPLIFY/NULLIFY/REDIRECT/STALL) already reflected in each {@code FutureEvent}'s
 * current state. Emits one era-level {@code ProbabilityStateCalculated} before any {@code OutcomeApplied}
 * (design.md requirement: emission order).
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

        var resolutions = new ArrayList<OutcomeApplied>();
        var newTerminalResolutions = new ArrayList<TerminalResolution>();
        for (var indexedEventId : indexedEventIds) {
            var futureEvent = futureEvents.findById(indexedEventId.eventId());
            if (futureEvent.resolved()) {
                // already resolved in a prior call for this era — nothing to do
            } else if (futureEvent.stalled()) {
                addStalled(
                        gameId, eraNumber, indexedEventId, futureEvent, alreadyCarriedForward, newTerminalResolutions);
            } else {
                var outcomeApplied = resolveOne(futureEvent, gameId, eraNumber);
                resolutions.add(outcomeApplied);
                newTerminalResolutions.add(new TerminalResolution(
                        outcomeApplied.eventId(),
                        indexedEventId.revealIndex(),
                        TerminalResolution.TerminalState.OUTCOME_APPLIED,
                        outcomeApplied.winningOutcomeId()));
            }
        }
        if (newTerminalResolutions.isEmpty()) {
            return;
        }

        if (!resolutions.isEmpty()) {
            publisher.publish(TimelineEventEnvelope.create(
                    gameId,
                    ERA_AGGREGATE_TYPE,
                    gameId,
                    TimelineEventEnvelope.SCHEMA_VERSION_V1,
                    toProbabilityStateCalculated(gameId, eraNumber, resolutions),
                    clock));
            for (var outcomeApplied : resolutions) {
                publisher.publish(TimelineEventEnvelope.create(
                        outcomeApplied.eventId(),
                        FUTURE_EVENT_AGGREGATE_TYPE,
                        gameId,
                        TimelineEventEnvelope.SCHEMA_VERSION_V1,
                        outcomeApplied,
                        clock));
            }
        }
        publisher.publish(TimelineEventEnvelope.create(
                gameId,
                ERA_AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new EraResolutionCompleted(gameId, eraNumber, newTerminalResolutions),
                clock));
    }

    private void addStalled(
            UUID gameId,
            int eraNumber,
            IndexedEventId indexedEventId,
            FutureEvent futureEvent,
            Set<UUID> alreadyCarriedForward,
            List<TerminalResolution> newTerminalResolutions) {
        if (alreadyCarriedForward.contains(futureEvent.id())) {
            return;
        }
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
                                        o.outcomeId(), o.probability(), false, false))
                                .toList()))
                .toList();
        return new ProbabilityStateCalculated(gameId, eraNumber, eventStates);
    }
}
