package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.EraResolutionCompleted;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.ParadoxDetected;
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ParadoxType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

@ExtendWith(MockitoExtension.class)
class ResolveEraCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    TimelineEventPublisher publisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC);

    private ResolveEraCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ResolveEraCommandHandler(eraIndex, futureEvents, publisher, clock);
    }

    @Test
    void resolve_threeUnresolvedEvents_publishesEraResolutionCompletedAfterAllOutcomeAppliedInRevealOrder() {
        var eventId0 = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();
        var outcomeId0 = UUID.randomUUID();
        var outcomeId1 = UUID.randomUUID();
        var outcomeId2 = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(
                        new IndexedEventId(eventId0, 0),
                        new IndexedEventId(eventId1, 1),
                        new IndexedEventId(eventId2, 2)));
        given(futureEvents.findById(eventId0)).willReturn(draftedFutureEvent(eventId0, outcomeId0));
        given(futureEvents.findById(eventId1)).willReturn(draftedFutureEvent(eventId1, outcomeId1));
        given(futureEvents.findById(eventId2)).willReturn(draftedFutureEvent(eventId2, outcomeId2));

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(5)).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        assertThat(payloads.get(0)).isInstanceOf(ProbabilityStateCalculated.class);
        assertThat(payloads.subList(1, 4))
                .allSatisfy(payload -> assertThat(payload).isInstanceOf(OutcomeApplied.class));

        var eraResolutionCompleted = (EraResolutionCompleted) payloads.get(4);
        assertThat(eraResolutionCompleted.gameId()).isEqualTo(GAME_ID);
        assertThat(eraResolutionCompleted.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(eraResolutionCompleted.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(
                                eventId0, 0, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId0),
                        new TerminalResolution(
                                eventId1, 1, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId1),
                        new TerminalResolution(
                                eventId2, 2, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId2));
    }

    @Test
    void resolve_allEventsAlreadyResolved_publishesNothing() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(resolvedFutureEvent(eventId, outcomeId));

        handler.resolve(GAME_ID, ERA_NUMBER);

        then(publisher).should(never()).publish(any());
    }

    @Test
    void resolve_stalledEvent_excludedFromOutcomeAppliedAndReportedAsStalled() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = stalledFutureEvent(eventId, outcomeId);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var eraResolutionCompleted = (EraResolutionCompleted) captor.getValue().payload();
        assertThat(eraResolutionCompleted.terminalResolutions())
                .containsExactly(new TerminalResolution(eventId, 0, TerminalResolution.TerminalState.STALLED, null));
        then(eraIndex).should().add(eventId, GAME_ID, ERA_NUMBER + 1, 0);
    }

    @Test
    void resolve_stalledEvent_clearsStalledStateSoTheCarriedEventResolvesNormallyNextEra() {
        // Regression: without clearing, the same FutureEvent would still report stalled() == true when
        // resolve() runs again for eraNumber + 1, carrying it forward forever instead of resolving it.
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = stalledFutureEvent(eventId, outcomeId);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER);

        assertThat(futureEvent.stalled()).isFalse();
    }

    @Test
    void resolve_carriedEventNoLongerStalled_resolvesNormallyInTheNextEra() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var nextEraNumber = ERA_NUMBER + 1;
        var futureEvent = draftedFutureEvent(eventId, outcomeId);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, nextEraNumber))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, nextEraNumber);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        var terminalResolution = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .filter(EraResolutionCompleted.class::isInstance)
                .map(EraResolutionCompleted.class::cast)
                .findFirst()
                .orElseThrow()
                .terminalResolutions()
                .getFirst();
        assertThat(terminalResolution.terminalState()).isEqualTo(TerminalResolution.TerminalState.OUTCOME_APPLIED);
        assertThat(terminalResolution.winningOutcomeId()).isEqualTo(outcomeId);
    }

    @Test
    void resolve_mixOfStalledAndResolved_bothReportedInRevealOrder() {
        var stalledEventId = UUID.randomUUID();
        var resolvedEventId = UUID.randomUUID();
        var resolvedOutcomeId = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(stalledEventId, 0), new IndexedEventId(resolvedEventId, 1)));
        given(futureEvents.findById(stalledEventId)).willReturn(stalledFutureEvent(stalledEventId, UUID.randomUUID()));
        given(futureEvents.findById(resolvedEventId))
                .willReturn(draftedFutureEvent(resolvedEventId, resolvedOutcomeId));

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        var eraResolutionCompleted = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .filter(EraResolutionCompleted.class::isInstance)
                .map(EraResolutionCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(eraResolutionCompleted.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(stalledEventId, 0, TerminalResolution.TerminalState.STALLED, null),
                        new TerminalResolution(
                                resolvedEventId,
                                1,
                                TerminalResolution.TerminalState.OUTCOME_APPLIED,
                                resolvedOutcomeId));
    }

    @Test
    void resolve_stalledEventAlreadyCarriedForward_doesNotRepublish() {
        var eventId = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER + 1))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(stalledFutureEvent(eventId, UUID.randomUUID()));

        handler.resolve(GAME_ID, ERA_NUMBER);

        then(publisher).should(never()).publish(any());
        then(eraIndex).should(never()).add(any(), any(), org.mockito.ArgumentMatchers.eq(ERA_NUMBER + 1), anyInt());
    }

    @Test
    void resolve_calledTwiceForSameEra_secondCallDoesNotResolveTheCarriedEvent() {
        // Regression: addStalled clears stalled() as part of carrying the event forward, so by the second
        // call futureEvent.stalled() is already false. Without an alreadyCarriedForward guard ahead of the
        // resolved()/stalled() checks, the second call would fall through to the else branch and resolve
        // the event in eraNumber — cancelling the one-era delay and double-reporting it.
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = stalledFutureEvent(eventId, outcomeId);
        var carriedForwardIndex = new ArrayList<IndexedEventId>();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER + 1))
                .willAnswer(inv -> List.copyOf(carriedForwardIndex));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        willAnswer(inv -> {
                    carriedForwardIndex.add(new IndexedEventId(eventId, 0));
                    return null;
                })
                .given(eraIndex)
                .add(eventId, GAME_ID, ERA_NUMBER + 1, 0);

        handler.resolve(GAME_ID, ERA_NUMBER);
        handler.resolve(GAME_ID, ERA_NUMBER);

        assertThat(futureEvent.resolved()).isFalse();
        then(publisher).should(times(1)).publish(any());
    }

    @Test
    void resolve_sealedAndAnnihilatedOutcomes_reportedInProbabilityStateCalculated() {
        var eventId = UUID.randomUUID();
        var sealedOutcomeId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var plainOutcomeId = UUID.randomUUID();
        var futureEvent = FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(sealedOutcomeId, "sealed", 20),
                                new Outcome(annihilatedOutcomeId, "annihilated", 30),
                                new Outcome(plainOutcomeId, "plain", 50)))));
        futureEvent.sealOutcome(sealedOutcomeId);
        futureEvent.annihilateOutcome(annihilatedOutcomeId);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        var probabilityStateCalculated = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .filter(ProbabilityStateCalculated.class::isInstance)
                .map(ProbabilityStateCalculated.class::cast)
                .findFirst()
                .orElseThrow();
        var outcomeStates = probabilityStateCalculated.eventStates().getFirst().outcomes();
        assertThat(outcomeStates)
                .filteredOn(o -> o.outcomeId().equals(sealedOutcomeId))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.isSealed()).isTrue();
                    assertThat(o.isAnnihilated()).isFalse();
                });
        assertThat(outcomeStates)
                .filteredOn(o -> o.outcomeId().equals(annihilatedOutcomeId))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.isAnnihilated()).isTrue();
                    assertThat(o.isSealed()).isFalse();
                });
        assertThat(outcomeStates)
                .filteredOn(o -> o.outcomeId().equals(plainOutcomeId))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.isSealed()).isFalse();
                    assertThat(o.isAnnihilated()).isFalse();
                });
    }

    @Test
    void resolve_annihilatedOutcomeAtHighestProbability_publishesParadoxInsteadOfExcludingAndResolving() {
        // Before paradox detection, this exact case (annihilated outcome holds the top probability) resolved
        // by excluding the annihilated outcome and picking the next-highest — see FutureEventTest for that
        // exclusion rule still holding within resolve() itself. At this layer, that same input is now the
        // IMPOSSIBLE_ERASURE condition and is intercepted before resolve() is called at all.
        var eventId = UUID.randomUUID();
        var annihilatedHighest = UUID.randomUUID();
        var eligibleSecond = UUID.randomUUID();
        var futureEvent = FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(annihilatedHighest, "highest", 60),
                                new Outcome(eligibleSecond, "second", 40)))));
        futureEvent.annihilateOutcome(annihilatedHighest);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();
        assertThat(payloads).noneMatch(OutcomeApplied.class::isInstance);
        var paradoxDetected = payloads.stream()
                .filter(ParadoxDetected.class::isInstance)
                .map(ParadoxDetected.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(paradoxDetected.paradoxes())
                .singleElement()
                .satisfies(paradox -> assertThat(paradox.affectedOutcomeIds()).containsExactly(annihilatedHighest));
    }

    @Test
    void resolve_oneParadoxedAndTwoNormalEvents_publishesParadoxDetectedAndOmitsParadoxedFromTerminalResolutions() {
        var paradoxedEventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var paradoxedEvent = FutureEvent.replay(
                paradoxedEventId,
                List.of(new FutureEventDrafted(
                        paradoxedEventId,
                        List.of(
                                new Outcome(annihilatedOutcomeId, "annihilated", 60),
                                new Outcome(UUID.randomUUID(), "second", 40)))));
        paradoxedEvent.annihilateOutcome(annihilatedOutcomeId);

        var eventId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();
        var outcomeId1 = UUID.randomUUID();
        var outcomeId2 = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(
                        new IndexedEventId(paradoxedEventId, 0),
                        new IndexedEventId(eventId1, 1),
                        new IndexedEventId(eventId2, 2)));
        given(futureEvents.findById(paradoxedEventId)).willReturn(paradoxedEvent);
        given(futureEvents.findById(eventId1)).willReturn(draftedFutureEvent(eventId1, outcomeId1));
        given(futureEvents.findById(eventId2)).willReturn(draftedFutureEvent(eventId2, outcomeId2));

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        var payloads = captor.getAllValues().stream()
                .map(TimelineEventEnvelope::payload)
                .toList();

        var paradoxDetected = payloads.stream()
                .filter(ParadoxDetected.class::isInstance)
                .map(ParadoxDetected.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(paradoxDetected.gameId()).isEqualTo(GAME_ID);
        assertThat(paradoxDetected.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(paradoxDetected.paradoxes()).hasSize(1);
        var paradox = paradoxDetected.paradoxes().getFirst();
        assertThat(paradox.type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
        assertThat(paradox.affectedEventId()).isEqualTo(paradoxedEventId);
        assertThat(paradox.affectedOutcomeIds()).containsExactly(annihilatedOutcomeId);

        var outcomeAppliedEventIds = payloads.stream()
                .filter(OutcomeApplied.class::isInstance)
                .map(OutcomeApplied.class::cast)
                .map(OutcomeApplied::eventId)
                .toList();
        assertThat(outcomeAppliedEventIds).containsExactly(eventId1, eventId2);

        var eraResolutionCompleted = payloads.stream()
                .filter(EraResolutionCompleted.class::isInstance)
                .map(EraResolutionCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(eraResolutionCompleted.terminalResolutions())
                .containsExactly(
                        new TerminalResolution(
                                eventId1, 1, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId1),
                        new TerminalResolution(
                                eventId2, 2, TerminalResolution.TerminalState.OUTCOME_APPLIED, outcomeId2));
    }

    @Test
    void resolve_noParadoxes_publishesNoParadoxDetected() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(draftedFutureEvent(eventId, outcomeId));

        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(atLeastOnce()).publish(captor.capture());
        assertThat(captor.getAllValues().stream().map(TimelineEventEnvelope::payload))
                .noneMatch(ParadoxDetected.class::isInstance);
    }

    @Test
    void resolve_paradoxedEvent_staysUnresolvedSoASubsequentCallReEvaluatesIt() {
        var eventId = UUID.randomUUID();
        var annihilatedOutcomeId = UUID.randomUUID();
        var futureEvent = FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(annihilatedOutcomeId, "annihilated", 60),
                                new Outcome(UUID.randomUUID(), "second", 40)))));
        futureEvent.annihilateOutcome(annihilatedOutcomeId);

        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER);

        assertThat(futureEvent.resolved()).isFalse();
        assertThat(futureEvent.stalled()).isFalse();

        // A second call for the same era re-detects the still-unresolved paradox instead of skipping the event.
        handler.resolve(GAME_ID, ERA_NUMBER);

        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should(times(2)).publish(captor.capture());
        assertThat(captor.getAllValues().stream().map(TimelineEventEnvelope::payload))
                .allMatch(ParadoxDetected.class::isInstance);
    }

    private static FutureEvent stalledFutureEvent(UUID eventId, UUID outcomeId) {
        var event = draftedFutureEvent(eventId, outcomeId);
        event.markStalled();
        return event;
    }

    private static FutureEvent draftedFutureEvent(UUID eventId, UUID outcomeId) {
        return FutureEvent.replay(
                eventId, List.of(new FutureEventDrafted(eventId, List.of(new Outcome(outcomeId, "d", 100)))));
    }

    private static FutureEvent resolvedFutureEvent(UUID eventId, UUID outcomeId) {
        var outcomes = List.of(new Outcome(outcomeId, "d", 100));
        return FutureEvent.replay(
                eventId,
                List.of(
                        new FutureEventDrafted(eventId, outcomes),
                        new OutcomeApplied(GAME_ID, ERA_NUMBER, eventId, outcomeId, outcomes)));
    }
}
