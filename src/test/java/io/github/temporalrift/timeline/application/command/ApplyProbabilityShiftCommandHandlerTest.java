package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;
import io.github.temporalrift.timeline.domain.port.out.AmplifyStatePort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard;
import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort.LastCard.EffectKind;

@ExtendWith(MockitoExtension.class)
class ApplyProbabilityShiftCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final int ROUND_NUMBER = 1;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    ProbabilityRulesPort rules;

    @Mock
    AmplifyStatePort amplifyState;

    @Mock
    RoundLastCardPort roundLastCard;

    @Mock
    EventLastShiftPort eventLastShift;

    private ApplyProbabilityShiftCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ApplyProbabilityShiftCommandHandler(
                futureEvents, rules, amplifyState, roundLastCard, eventLastShift);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("AMPLIFY armed then SUPPRESS: doubles the configured suppress magnitude")
    void apply_amplifyArmed_suppress_doublesConfiguredMagnitude() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var other1 = UUID.randomUUID();
        var other2 = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, target, 50, other1, 30, other2, 20);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.suppressShift()).willReturn(-10);
        given(amplifyState.isPending(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(true);

        handler.apply(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, new ProbabilityShift.Suppress(target));

        // -10 configured, doubled to -20 by the armed AMPLIFY
        assertThat(probabilityOf(futureEvent, target)).isEqualTo(30);
        then(amplifyState).should().clear(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        var shiftCaptor = ArgumentCaptor.forClass(EventShift.class);
        then(eventLastShift)
                .should()
                .save(eq(GAME_ID), eq(ERA_NUMBER), eq(ROUND_NUMBER), eq(eventId), shiftCaptor.capture());
        assertThat(shiftCaptor.getValue().magnitude()).isEqualTo(-20);
        assertThat(shiftCaptor.getValue().shiftType()).isEqualTo(ShiftType.SUPPRESS);
    }

    @Test
    void apply_amplifyNotPending_appliesConfiguredMagnitudeUnchanged() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var other1 = UUID.randomUUID();
        var other2 = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, target, 50, other1, 30, other2, 20);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift()).willReturn(20);
        given(amplifyState.isPending(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(false);

        handler.apply(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, new ProbabilityShift.Push(target));

        assertThat(probabilityOf(futureEvent, target)).isEqualTo(70);
        then(amplifyState).should(never()).clear(GAME_ID, ERA_NUMBER, ROUND_NUMBER);
    }

    @Test
    void apply_recordsRoundLastCardAsEventEffect() {
        var eventId = UUID.randomUUID();
        var target = UUID.randomUUID();
        var other1 = UUID.randomUUID();
        var other2 = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, target, 50, other1, 30, other2, 20);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.pushShift()).willReturn(20);
        given(amplifyState.isPending(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(false);

        handler.apply(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, new ProbabilityShift.Push(target));

        then(roundLastCard)
                .should()
                .save(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.EVENT_EFFECT, eventId));
    }

    private static FutureEvent draftedFutureEvent(
            UUID eventId, UUID outcomeA, int probA, UUID outcomeB, int probB, UUID outcomeC, int probC) {
        return FutureEvent.replay(
                eventId,
                List.of(new FutureEventDrafted(
                        eventId,
                        List.of(
                                new Outcome(outcomeA, "a", probA),
                                new Outcome(outcomeB, "b", probB),
                                new Outcome(outcomeC, "c", probC)))));
    }

    private static int probabilityOf(FutureEvent event, UUID outcomeId) {
        return event.outcomes().stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow()
                .probability();
    }
}
