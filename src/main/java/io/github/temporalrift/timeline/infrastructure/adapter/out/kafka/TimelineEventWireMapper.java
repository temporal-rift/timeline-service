package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.model.OutcomeAppliedPayload;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.model.OutcomeAppliedProbabilityState;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.model.ProbabilityStateCalculatedEventState;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.model.ProbabilityStateCalculatedOutcomeState;
import io.github.temporalrift.timeline.infrastructure.adapter.out.kafka.model.ProbabilityStateCalculatedPayload;

@Mapper(componentModel = "spring")
interface TimelineEventWireMapper {

    @Mapping(target = "finalProbabilities", source = "finalOutcomes")
    OutcomeAppliedPayload toWire(OutcomeApplied event);

    OutcomeAppliedProbabilityState toWire(Outcome outcome);

    ProbabilityStateCalculatedPayload toWire(ProbabilityStateCalculated event);

    ProbabilityStateCalculatedEventState toWire(ProbabilityStateCalculated.EventState eventState);

    ProbabilityStateCalculatedOutcomeState toWire(ProbabilityStateCalculated.OutcomeState outcomeState);
}
