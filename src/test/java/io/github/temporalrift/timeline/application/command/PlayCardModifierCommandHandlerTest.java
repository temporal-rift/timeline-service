package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.application.port.in.PlayCardModifierUseCase.CardModifier;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
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
class PlayCardModifierCommandHandlerTest {

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

    private PlayCardModifierCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PlayCardModifierCommandHandler(futureEvents, rules, amplifyState, roundLastCard, eventLastShift);
    }

    @Test
    void amplify_armsPendingDoublingAndRecordsLastCard() {
        handler.play(new CardModifier.Amplify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        then(amplifyState).should().arm(GAME_ID, ERA_NUMBER, ROUND_NUMBER);
        then(roundLastCard)
                .should()
                .record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.AMPLIFY_ARMED, null));
    }

    @Test
    void nullify_targetingArmedAmplify_clearsPendingDoublingWithNoProbabilityChange() {
        given(roundLastCard.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(Optional.of(new LastCard(EffectKind.AMPLIFY_ARMED, null)));

        handler.play(new CardModifier.Nullify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        then(amplifyState).should().clear(GAME_ID, ERA_NUMBER, ROUND_NUMBER);
        then(futureEvents).should(never()).findById(any());
        then(roundLastCard).should().record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.NOOP, null));
    }

    @Test
    void nullify_targetingEventEffect_restoresExactSnapshot() {
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var snapshot = Map.of(outcomeA, 33, outcomeB, 33, outcomeC, 34);
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 43, outcomeB, 28, outcomeC, 29);

        given(roundLastCard.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(Optional.of(new LastCard(EffectKind.EVENT_EFFECT, eventId)));
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(new EventShift(ShiftType.PUSH, null, outcomeA, 10, snapshot)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new CardModifier.Nullify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        assertThat(probabilityOf(futureEvent, outcomeA)).isEqualTo(33);
        assertThat(probabilityOf(futureEvent, outcomeB)).isEqualTo(33);
        assertThat(probabilityOf(futureEvent, outcomeC)).isEqualTo(34);
        then(eventLastShift).should().clear(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId);
    }

    @Test
    void nullify_targetingStalledEvent_clearsStalledState() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeId, 100);
        futureEvent.markStalled();

        given(roundLastCard.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(Optional.of(new LastCard(EffectKind.EVENT_STALLED, eventId)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new CardModifier.Nullify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        assertThat(futureEvent.stalled()).isFalse();
    }

    @Test
    void nullify_targetingNoOp_doesNothing() {
        given(roundLastCard.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER))
                .willReturn(Optional.of(new LastCard(EffectKind.NOOP, null)));

        handler.play(new CardModifier.Nullify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        then(futureEvents).should(never()).findById(any());
        then(amplifyState).should(never()).clear(any(), anyInt(), anyInt());
    }

    @Test
    void nullify_noPriorCardThisRound_isNoOp() {
        given(roundLastCard.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(Optional.empty());

        handler.play(new CardModifier.Nullify(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        then(futureEvents).should(never()).findById(any());
        then(roundLastCard).should(never()).record(any(), anyInt(), anyInt(), any());
    }

    @Test
    void redirect_movesLastShiftToNewOutcome() {
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var snapshot = Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20);
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 70, outcomeB, 18, outcomeC, 12);

        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(new EventShift(ShiftType.PUSH, null, outcomeA, 20, snapshot)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.play(new CardModifier.Redirect(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, outcomeB));

        // Undo to the pre-shift snapshot (50/30/20), then reapply the same PUSH magnitude (20) to B instead
        // of A: B rises 30 -> 50, and A/C absorb the decrease proportionally to their 50/20 shares.
        assertThat(probabilityOf(futureEvent, outcomeB)).isEqualTo(50);
        assertThat(probabilityOf(futureEvent, outcomeA)).isEqualTo(36);
        assertThat(probabilityOf(futureEvent, outcomeC)).isEqualTo(14);
        assertThat(probabilityOf(futureEvent, outcomeA)
                        + probabilityOf(futureEvent, outcomeB)
                        + probabilityOf(futureEvent, outcomeC))
                .isEqualTo(100);
    }

    @Test
    void redirect_recordsTheStateBeforeItRanNotTheOriginalPreShiftSnapshot() {
        // Regression for a NULLIFY-after-REDIRECT bug: the snapshot stored for undo must be the state right
        // before REDIRECT ran (70/18/12, post-original-PUSH), not the original shift's own pre-shift
        // snapshot (50/30/20) — otherwise a later NULLIFY would undo both the REDIRECT and the original PUSH.
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var originalPreShiftSnapshot = Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20);
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 70, outcomeB, 18, outcomeC, 12);

        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(new EventShift(ShiftType.PUSH, null, outcomeA, 20, originalPreShiftSnapshot)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.play(new CardModifier.Redirect(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, outcomeB));

        var captor = ArgumentCaptor.forClass(EventShift.class);
        then(eventLastShift).should().record(any(), anyInt(), anyInt(), any(), captor.capture());
        assertThat(captor.getValue().preShiftSnapshot()).isEqualTo(Map.of(outcomeA, 70, outcomeB, 18, outcomeC, 12));
    }

    @Test
    void redirect_noPriorShiftOnTargetEvent_isNoOpAndRecordsRoundLastCardAsNoOp() {
        var eventId = UUID.randomUUID();
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId)).willReturn(Optional.empty());

        handler.play(new CardModifier.Redirect(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, UUID.randomUUID()));

        then(futureEvents).should(never()).findById(any());
        then(roundLastCard).should().record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.NOOP, null));
    }

    @Test
    void redirect_swingToItsOwnRecordedSource_isNoOpAndRecordsRoundLastCardAsNoOp() {
        var eventId = UUID.randomUUID();
        var sourceOutcomeId = UUID.randomUUID();
        var targetOutcomeId = UUID.randomUUID();
        var thirdOutcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, sourceOutcomeId, 40, targetOutcomeId, 40, thirdOutcomeId, 20);

        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(new EventShift(
                        ShiftType.SWING,
                        sourceOutcomeId,
                        targetOutcomeId,
                        30,
                        Map.of(sourceOutcomeId, 40, targetOutcomeId, 40, thirdOutcomeId, 20))));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        // Redirecting back to the SWING's own recorded source would otherwise throw
        // IllegalArgumentException ("SWING requires distinct source and target outcomes").
        handler.play(new CardModifier.Redirect(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, sourceOutcomeId));

        then(eventLastShift).should(never()).record(any(), anyInt(), anyInt(), any(), any());
        then(roundLastCard).should().record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.NOOP, null));
    }

    @Test
    void redirect_unknownTargetOutcome_isNoOpAndRecordsRoundLastCardAsNoOp() {
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 50, outcomeB, 30, outcomeC, 20);

        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(new EventShift(
                        ShiftType.PUSH, null, outcomeA, 20, Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20))));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new CardModifier.Redirect(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId, UUID.randomUUID()));

        then(eventLastShift).should(never()).record(any(), anyInt(), anyInt(), any(), any());
        then(roundLastCard).should().record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.NOOP, null));
    }

    @Test
    void stall_marksEventStalledAndRecordsLastCard() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeId, 100);
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.play(new CardModifier.Stall(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId));

        assertThat(futureEvent.stalled()).isTrue();
        then(roundLastCard)
                .should()
                .record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.EVENT_STALLED, eventId));
    }

    @Test
    void noOp_recordsLastCardAsNoOpWithoutTouchingAnyFutureEvent() {
        handler.play(new CardModifier.NoOp(GAME_ID, ERA_NUMBER, ROUND_NUMBER));

        then(futureEvents).should(never()).findById(any());
        then(roundLastCard).should().record(GAME_ID, ERA_NUMBER, ROUND_NUMBER, new LastCard(EffectKind.NOOP, null));
    }

    private static FutureEvent draftedFutureEvent(UUID eventId, UUID outcomeId, int probability) {
        return FutureEvent.replay(
                eventId, List.of(new FutureEventDrafted(eventId, List.of(new Outcome(outcomeId, "d", probability)))));
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
