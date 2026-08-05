package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import java.util.LinkedHashMap;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.EraResolutionCompletedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.OutcomeAppliedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.OutcomeAppliedProbabilityState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedEventState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedOutcomeState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedPayload;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;

@Mapper(componentModel = "spring")
interface TimelineEventWireMapper {

    @Mapping(target = "finalProbabilities", source = "finalOutcomes")
    OutcomeAppliedPayload toWire(OutcomeApplied event);

    OutcomeAppliedProbabilityState toWire(Outcome outcome);

    ProbabilityStateCalculatedPayload toWire(ProbabilityStateCalculated event);

    ProbabilityStateCalculatedEventState toWire(ProbabilityStateCalculated.EventState eventState);

    ProbabilityStateCalculatedOutcomeState toWire(ProbabilityStateCalculated.OutcomeState outcomeState);

    EraResolutionCompletedPayload toWire(EraResolutionCompleted event);

    /**
     * The generated {@code EraResolutionCompletedPayload.terminalResolutions} is untyped {@code List<Object>} —
     * jsonschema2pojo doesn't generate a class for the spec's {@code oneOf} (design.md Decision 5).
     */
    default List<Object> toWireTerminalResolutions(List<TerminalResolution> terminalResolutions) {
        return terminalResolutions.stream()
                .map(TimelineEventWireMapper::toWireTerminalResolution)
                .map(Object.class::cast)
                .toList();
    }

    private static LinkedHashMap<String, Object> toWireTerminalResolution(TerminalResolution terminalResolution) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put("eventId", terminalResolution.eventId());
        wire.put("revealIndex", terminalResolution.revealIndex());
        wire.put("terminalState", terminalResolution.terminalState().name());
        if (terminalResolution.winningOutcomeId() != null) {
            wire.put("winningOutcomeId", terminalResolution.winningOutcomeId());
        }
        return wire;
    }
}
