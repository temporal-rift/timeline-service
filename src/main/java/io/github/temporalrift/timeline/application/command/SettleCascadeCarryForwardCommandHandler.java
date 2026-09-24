package io.github.temporalrift.timeline.application.command;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.SettleCascadeCarryForwardUseCase;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.event.TerminalResolution.TerminalState;
import io.github.temporalrift.timeline.domain.futureevent.FutureEventNotFoundException;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort.CascadeCarryForward;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

/**
 * Confirms an armed CASCADE into a pending carry-forward for the next era when its named outcome is erased and its
 * event carries ({@code STALLED} or {@code CASCADED}); otherwise, or when its target event is not part of the era
 * at all, rejects it privately in the same era. Erasure state is final whenever this runs: ANNIHILATE applies during
 * round replay and paradox resolution never un-annihilates.
 */
@Service
class SettleCascadeCarryForwardCommandHandler implements SettleCascadeCarryForwardUseCase {

    static final String REASON_TARGET_NOT_ERASED = "TARGET_NOT_ERASED";
    static final String REASON_EVENT_NOT_CARRIED = "EVENT_NOT_CARRIED";
    static final String REASON_TARGET_NOT_IN_ERA = "TARGET_NOT_IN_ERA";

    private static final String FUTURE_EVENT_AGGREGATE_TYPE = "FutureEvent";

    private final CascadeCarryForwardPort cascadeCarryForward;
    private final FutureEventRepository futureEvents;
    private final FutureEventEraIndexPort eraIndex;
    private final TimelineEventPublisher publisher;
    private final Clock clock;

    SettleCascadeCarryForwardCommandHandler(
            CascadeCarryForwardPort cascadeCarryForward,
            FutureEventRepository futureEvents,
            FutureEventEraIndexPort eraIndex,
            TimelineEventPublisher publisher,
            Clock clock) {
        this.cascadeCarryForward = cascadeCarryForward;
        this.futureEvents = futureEvents;
        this.eraIndex = eraIndex;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    public void settle(UUID gameId, int eraNumber, List<TerminalResolution> terminalResolutions) {
        var terminalStateByEvent = terminalResolutions.stream()
                .collect(Collectors.toMap(TerminalResolution::eventId, TerminalResolution::terminalState, (a, _) -> a));
        var eraEventIds = eraIndex.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(IndexedEventId::eventId)
                .collect(Collectors.toSet());
        for (var armed : cascadeCarryForward.findByGameAndEra(gameId, eraNumber)) {
            var terminalState = terminalStateByEvent.get(armed.eventId());
            if (!eraEventIds.contains(armed.eventId())) {
                reject(gameId, eraNumber, armed, REASON_TARGET_NOT_IN_ERA);
            } else if (terminalState != null) {
                settleOne(gameId, eraNumber, armed, terminalState);
            }
        }
    }

    private void settleOne(UUID gameId, int eraNumber, CascadeCarryForward armed, TerminalState terminalState) {
        if (!isErased(armed.eventId(), armed.outcomeId())) {
            reject(gameId, eraNumber, armed, REASON_TARGET_NOT_ERASED);
        } else if (terminalState == TerminalState.OUTCOME_APPLIED) {
            reject(gameId, eraNumber, armed, REASON_EVENT_NOT_CARRIED);
        } else {
            cascadeCarryForward.confirm(gameId, eraNumber, armed.eventId(), armed.outcomeId(), eraNumber + 1);
        }
    }

    private boolean isErased(UUID eventId, UUID outcomeId) {
        try {
            return futureEvents.findById(eventId).outcomes().stream()
                    .anyMatch(o -> o.outcomeId().equals(outcomeId) && o.annihilated());
        } catch (FutureEventNotFoundException _) {
            return false;
        }
    }

    private void reject(UUID gameId, int eraNumber, CascadeCarryForward armed, String reason) {
        cascadeCarryForward.delete(gameId, eraNumber, armed.eventId(), armed.outcomeId());
        publisher.publish(TimelineEventEnvelope.create(
                armed.eventId(),
                FUTURE_EVENT_AGGREGATE_TYPE,
                gameId,
                TimelineEventEnvelope.SCHEMA_VERSION_V1,
                new SpecialRejectedEvent(
                        gameId,
                        eraNumber,
                        armed.playerId(),
                        "CASCADE",
                        null,
                        armed.eventId(),
                        armed.outcomeId(),
                        reason),
                clock));
    }
}
