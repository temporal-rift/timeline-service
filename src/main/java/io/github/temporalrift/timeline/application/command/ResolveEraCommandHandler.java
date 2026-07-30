package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.ResolveEraUseCase;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Resolves every {@code FutureEvent} drawn for an era: highest-probability wins, deterministic
 * {@code outcomeId} tie-break, no card/special/paradox logic (design.md Decision 3). Emits one
 * era-level {@code ProbabilityStateCalculated} before any {@code OutcomeApplied} (design.md
 * requirement: emission order).
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
        var resolutions = new ArrayList<OutcomeApplied>();
        for (var eventId : eraIndex.findEventIdsByGameIdAndEraNumber(gameId, eraNumber)) {
            var futureEvent = futureEvents.findById(eventId);
            if (futureEvent.resolved()) {
                continue;
            }
            resolutions.add(resolveOne(futureEvent, gameId, eraNumber));
        }
        if (resolutions.isEmpty()) {
            return;
        }

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

    private OutcomeApplied resolveOne(FutureEvent futureEvent, UUID gameId, int eraNumber) {
        var outcomeApplied = futureEvent.resolve(gameId, eraNumber);
        futureEvents.append(futureEvent.id(), outcomeApplied);
        eraIndex.markResolved(futureEvent.id());
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
