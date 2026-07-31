package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import io.github.temporalrift.timeline.domain.event.ProbabilityStateCalculated;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
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
        then(publisher).should(org.mockito.Mockito.times(5)).publish(captor.capture());
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
