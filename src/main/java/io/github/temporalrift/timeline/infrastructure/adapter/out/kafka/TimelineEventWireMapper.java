package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.BandedProbabilityPublishedEventBandState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.BandedProbabilityPublishedOutcomeBandState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.BandedProbabilityPublishedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.CorruptInversionConfirmedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.EraResolutionCompletedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.EraTerminalResolution;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.OutcomeAppliedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.OutcomeAppliedProbabilityState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxCascadedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxCascadedProbabilityState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxDetectedParadox;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxDetectedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxResolutionPhaseStartedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxResolvedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedEventState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedOutcomeState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateCalculatedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ResolutionFailedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ResolutionWarningPayload;
import io.github.temporalrift.timeline.domain.event.BandedProbabilityPublished;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.ResolutionWarning;
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

    EraTerminalResolution toWire(TerminalResolution terminalResolution);

    ParadoxDetectedPayload toWire(ParadoxDetected event);

    ParadoxDetectedParadox toWire(ParadoxDetected.Paradox paradox);

    ParadoxResolutionPhaseStartedPayload toWire(ParadoxResolutionPhaseStarted event);

    ParadoxCascadedPayload toWire(ParadoxCascaded event);

    ParadoxCascadedProbabilityState toCarryForwardProbabilityState(Outcome outcome);

    ParadoxResolvedPayload toWire(ParadoxResolved event);

    BandedProbabilityPublishedPayload toWire(BandedProbabilityPublished event);

    BandedProbabilityPublishedEventBandState toWire(BandedProbabilityPublished.EventState eventState);

    BandedProbabilityPublishedOutcomeBandState toWire(BandedProbabilityPublished.OutcomeState outcomeState);

    CorruptInversionConfirmedPayload toWire(CorruptInversionConfirmed event);

    ResolutionFailedPayload toWire(ResolutionFailed event);

    ResolutionWarningPayload toWire(ResolutionWarning event);
}
