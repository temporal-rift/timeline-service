package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.SpecialRejectedEvent;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.event.TerminalResolution.TerminalState;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort.CascadeCarryForward;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;
import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort.IndexedEventId;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventEnvelope;
import io.github.temporalrift.timeline.domain.port.out.TimelineEventPublisher;

@ExtendWith(MockitoExtension.class)
class SettleCascadeCarryForwardCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 2;

    private final UUID eventId = UUID.randomUUID();
    private final UUID outcomeId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    CascadeCarryForwardPort cascadeCarryForward;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    FutureEventEraIndexPort eraIndex;

    @Mock
    TimelineEventPublisher publisher;

    @ParameterizedTest
    @EnumSource(
            value = TerminalState.class,
            names = {"STALLED", "CASCADED"})
    void settle_erasedOutcomeOnCarriedEvent_confirmsCarryForwardIntoNextEra(TerminalState carried) {
        givenArmedCascade(futureEvent(true));

        handler().settle(GAME_ID, ERA_NUMBER, List.of(terminal(carried)));

        then(cascadeCarryForward).should().confirm(GAME_ID, ERA_NUMBER, eventId, outcomeId, ERA_NUMBER + 1);
        then(cascadeCarryForward).should(never()).delete(any(), anyInt(), any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void settle_erasedOutcomeOnNormallyResolvedEvent_rejectsAsNotCarried() {
        givenArmedCascade(futureEvent(true));

        handler().settle(GAME_ID, ERA_NUMBER, List.of(terminal(TerminalState.OUTCOME_APPLIED)));

        assertRejected("EVENT_NOT_CARRIED");
    }

    @Test
    void settle_unerasedOutcome_rejectsAsNotErasedEvenWhenTheEventCarries() {
        givenArmedCascade(futureEvent(false));

        handler().settle(GAME_ID, ERA_NUMBER, List.of(terminal(TerminalState.STALLED)));

        assertRejected("TARGET_NOT_ERASED");
    }

    @Test
    void settle_eventWithoutTerminalYet_leavesTheArmedCascadeUntouched() {
        given(cascadeCarryForward.findByGameAndEra(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new CascadeCarryForward(playerId, eventId, outcomeId)));
        givenEventInEra();

        handler().settle(GAME_ID, ERA_NUMBER, List.of());

        then(cascadeCarryForward).should(never()).confirm(any(), anyInt(), any(), any(), anyInt());
        then(cascadeCarryForward).should(never()).delete(any(), anyInt(), any(), any());
        then(publisher).should(never()).publish(any());
    }

    @Test
    void settle_targetEventOutsideTheEra_rejectsAsNotInEra() {
        given(cascadeCarryForward.findByGameAndEra(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new CascadeCarryForward(playerId, eventId, outcomeId)));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER)).willReturn(List.of());

        handler().settle(GAME_ID, ERA_NUMBER, List.of());

        assertRejected("TARGET_NOT_IN_ERA");
    }

    @Test
    void settle_stalledEventAlreadyIndexedIntoNextEra_stillConfirmsCarryForward() {
        given(cascadeCarryForward.findByGameAndEra(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new CascadeCarryForward(playerId, eventId, outcomeId)));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER)).willReturn(List.of());
        given(futureEvents.findById(eventId)).willReturn(futureEvent(true));
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER + 1))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));

        handler().settle(GAME_ID, ERA_NUMBER, List.of(terminal(TerminalState.STALLED)));

        then(cascadeCarryForward).should().confirm(GAME_ID, ERA_NUMBER, eventId, outcomeId, ERA_NUMBER + 1);
    }

    private SettleCascadeCarryForwardCommandHandler handler() {
        return new SettleCascadeCarryForwardCommandHandler(
                cascadeCarryForward, futureEvents, eraIndex, publisher, clock);
    }

    private void givenArmedCascade(FutureEvent futureEvent) {
        given(cascadeCarryForward.findByGameAndEra(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new CascadeCarryForward(playerId, eventId, outcomeId)));
        givenEventInEra();
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
    }

    private void givenEventInEra() {
        given(eraIndex.findByGameIdAndEraNumber(GAME_ID, ERA_NUMBER))
                .willReturn(List.of(new IndexedEventId(eventId, 0)));
    }

    private FutureEvent futureEvent(boolean erased) {
        var futureEvent = FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(new Outcome(outcomeId, "named", 50), new Outcome(UUID.randomUUID(), "o", 50)))));
        if (erased) {
            futureEvent.annihilateOutcome(outcomeId);
        }
        return futureEvent;
    }

    private TerminalResolution terminal(TerminalState state) {
        return new TerminalResolution(
                eventId, 0, state, state == TerminalState.OUTCOME_APPLIED ? UUID.randomUUID() : null);
    }

    private void assertRejected(String reason) {
        then(cascadeCarryForward).should().delete(GAME_ID, ERA_NUMBER, eventId, outcomeId);
        then(cascadeCarryForward).should(never()).confirm(any(), anyInt(), any(), any(), anyInt());
        var captor = ArgumentCaptor.forClass(TimelineEventEnvelope.class);
        then(publisher).should().publish(captor.capture());
        var rejected = (SpecialRejectedEvent) captor.getValue().payload();
        assertThat(rejected.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(rejected.playerId()).isEqualTo(playerId);
        assertThat(rejected.specialAction()).isEqualTo("CASCADE");
        assertThat(rejected.targetEventId()).isEqualTo(eventId);
        assertThat(rejected.targetOutcomeId()).isEqualTo(outcomeId);
        assertThat(rejected.reason()).isEqualTo(reason);
    }
}
