package io.github.temporalrift.timeline.infrastructure.adapter.out.kafka;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.AdjustedBandsPublishedEventBandState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.AdjustedBandsPublishedOutcomeBandState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.AdjustedBandsPublishedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.CascadeCarriedForwardPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainBrokenPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainCompletedChainLink;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainCompletedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainLinkAddedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainLinkInvalidatedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainLinkThreadedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainProtectionArmedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainProtectionConsumedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ChainReAnchoredPayload;
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
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateRevealedOutcomeState;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ProbabilityStateRevealedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ResolutionFailedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.SpecialRejectedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ThreadRejectedPayload;
import io.github.temporalrift.timeline.domain.event.AdjustedBandsPublished;
import io.github.temporalrift.timeline.domain.event.CascadeCarriedForwardEvent;
import io.github.temporalrift.timeline.domain.event.ChainBrokenEvent;
import io.github.temporalrift.timeline.domain.event.ChainCompletedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkAddedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkInvalidatedEvent;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreadedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionArmedEvent;
import io.github.temporalrift.timeline.domain.event.ChainProtectionConsumedEvent;
import io.github.temporalrift.timeline.domain.event.ChainReAnchoredEvent;
import io.github.temporalrift.timeline.domain.event.CorruptInversionConfirmed;
import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxCascaded;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ParadoxResolutionPhaseStarted;
import io.github.temporalrift.timeline.domain.event.ParadoxResolved;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateRevealed;
import io.github.temporalrift.timeline.domain.event.ResolutionFailed;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.event.ThreadRejectedEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;

@Mapper(componentModel = "spring")
interface TimelineEventWireMapper {

    @Mapping(target = "finalProbabilities", source = "finalOutcomes")
    OutcomeAppliedPayload toWire(OutcomeApplied event);

    OutcomeAppliedProbabilityState toWire(Outcome outcome);

    ProbabilityStateCalculatedPayload toWire(ProbabilityStateCalculated event);

    ProbabilityStateCalculatedEventState toWire(ProbabilityStateCalculated.EventState eventState);

    ProbabilityStateCalculatedOutcomeState toWire(ProbabilityStateCalculated.OutcomeState outcomeState);

    ProbabilityStateRevealedPayload toWire(ProbabilityStateRevealed event);

    ProbabilityStateRevealedOutcomeState toWire(ProbabilityStateRevealed.OutcomeState outcomeState);

    EraResolutionCompletedPayload toWire(EraResolutionCompleted event);

    EraTerminalResolution toWire(TerminalResolution terminalResolution);

    ParadoxDetectedPayload toWire(ParadoxDetected event);

    ParadoxDetectedParadox toWire(ParadoxDetected.Paradox paradox);

    ParadoxResolutionPhaseStartedPayload toWire(ParadoxResolutionPhaseStarted event);

    ParadoxCascadedPayload toWire(ParadoxCascaded event);

    ParadoxCascadedProbabilityState toCarryForwardProbabilityState(Outcome outcome);

    ParadoxResolvedPayload toWire(ParadoxResolved event);

    AdjustedBandsPublishedPayload toWire(AdjustedBandsPublished event);

    AdjustedBandsPublishedEventBandState toWire(AdjustedBandsPublished.EventState eventState);

    AdjustedBandsPublishedOutcomeBandState toWire(AdjustedBandsPublished.OutcomeState outcomeState);

    CorruptInversionConfirmedPayload toWire(CorruptInversionConfirmed event);

    ChainLinkThreadedPayload toWire(ChainLinkThreadedEvent event);

    ChainLinkAddedPayload toWire(ChainLinkAddedEvent event);

    ChainCompletedPayload toWire(ChainCompletedEvent event);

    ChainCompletedChainLink toWire(ChainCompletedEvent.ChainLinkEntry entry);

    ChainBrokenPayload toWire(ChainBrokenEvent event);

    ChainLinkInvalidatedPayload toWire(ChainLinkInvalidatedEvent event);

    ThreadRejectedPayload toWire(ThreadRejectedEvent event);

    SpecialRejectedPayload toWire(SpecialRejectedEvent event);

    ChainProtectionArmedPayload toWire(ChainProtectionArmedEvent event);

    ChainProtectionConsumedPayload toWire(ChainProtectionConsumedEvent event);

    ChainReAnchoredPayload toWire(ChainReAnchoredEvent event);

    CascadeCarriedForwardPayload toWire(CascadeCarriedForwardEvent event);

    ResolutionFailedPayload toWire(ResolutionFailed event);
}
