package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.futureevent.FutureEvent;
import io.github.temporalrift.timeline.domain.futureevent.Outcome;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift;
import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort.EventShift.ShiftType;
import io.github.temporalrift.timeline.domain.port.out.FutureEventRepository;
import io.github.temporalrift.timeline.domain.port.out.PendingCorruptPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;
import io.github.temporalrift.timeline.domain.port.out.RoundCardByPlayerPort;
import io.github.temporalrift.timeline.domain.port.out.RoundCardByPlayerPort.PlayerCard;

@ExtendWith(MockitoExtension.class)
class ResolvePendingCorruptCommandHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 1;
    private static final int ROUND_NUMBER = 1;
    private static final UUID TARGET_PLAYER_ID = UUID.randomUUID();

    @Mock
    PendingCorruptPort pendingCorrupt;

    @Mock
    RoundCardByPlayerPort roundCardByPlayer;

    @Mock
    EventLastShiftPort eventLastShift;

    @Mock
    FutureEventRepository futureEvents;

    @Mock
    ProbabilityRulesPort rules;

    private ResolvePendingCorruptCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ResolvePendingCorruptCommandHandler(
                pendingCorrupt, roundCardByPlayer, eventLastShift, futureEvents, rules);
    }

    private static EventShift asEventShift(PlayerCard card) {
        return new EventShift(
                card.shiftType(),
                card.sourceOutcomeId(),
                card.targetOutcomeId(),
                card.magnitude(),
                card.preShiftSnapshot());
    }

    @Test
    void resolve_noPendingCorrupts_doesNothing() {
        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of());

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then_noFutureEventInteractions();
    }

    @Test
    void resolve_pendingCorruptWithNoCorrelatedCard_doesNothing() {
        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(TARGET_PLAYER_ID));
        given(roundCardByPlayer.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, TARGET_PLAYER_ID))
                .willReturn(Optional.empty());

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        then_noFutureEventInteractions();
    }

    @Test
    void resolve_correlatedPush_invertsToSuppressEffect() {
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        // FutureEvent's current state already reflects the original PUSH(a, +20) from 50/30/20.
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 70, outcomeB, 18, outcomeC, 12);
        var preShiftSnapshot = Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20);
        var card = new PlayerCard(eventId, ShiftType.PUSH, null, outcomeA, 20, preShiftSnapshot);

        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(TARGET_PLAYER_ID));
        given(roundCardByPlayer.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, TARGET_PLAYER_ID))
                .willReturn(Optional.of(card));
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(asEventShift(card)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Restored to 50/30/20, then SUPPRESS(a, -20) applied: a -> 30, redistributed proportionally.
        assertThat(probabilityOf(futureEvent, outcomeA)).isEqualTo(30);
        assertThat(probabilityOf(futureEvent, outcomeB)).isEqualTo(42);
        assertThat(probabilityOf(futureEvent, outcomeC)).isEqualTo(28);
    }

    @Test
    void resolve_correlatedSwing_reversesSourceAndTarget() {
        var eventId = UUID.randomUUID();
        var source = UUID.randomUUID();
        var target = UUID.randomUUID();
        var other = UUID.randomUUID();
        // FutureEvent's current state already reflects the original SWING(source -> target, 30).
        var futureEvent = draftedFutureEvent(eventId, source, 20, target, 60, other, 20);
        var preShiftSnapshot = Map.of(source, 50, target, 30, other, 20);
        var card = new PlayerCard(eventId, ShiftType.SWING, source, target, 30, preShiftSnapshot);

        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(TARGET_PLAYER_ID));
        given(roundCardByPlayer.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, TARGET_PLAYER_ID))
                .willReturn(Optional.of(card));
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(asEventShift(card)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);
        given(rules.probabilityFloor()).willReturn(0);
        given(rules.probabilityCeiling()).willReturn(90);

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        // Restored to 50/30/20, then SWING(target -> source, 30) applied: source 50->80, target 30->0.
        assertThat(probabilityOf(futureEvent, source)).isEqualTo(80);
        assertThat(probabilityOf(futureEvent, target)).isEqualTo(0);
        assertThat(probabilityOf(futureEvent, other)).isEqualTo(20);
    }

    @Test
    void resolve_correlatedCardTargetOutcomeSealed_neitherOriginalNorInvertedEffectApplies() {
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 70, outcomeB, 18, outcomeC, 12);
        futureEvent.sealOutcome(outcomeA);
        var preShiftSnapshot = Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20);
        var card = new PlayerCard(eventId, ShiftType.PUSH, null, outcomeA, 20, preShiftSnapshot);

        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(TARGET_PLAYER_ID));
        given(roundCardByPlayer.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, TARGET_PLAYER_ID))
                .willReturn(Optional.of(card));
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId))
                .willReturn(Optional.of(asEventShift(card)));
        given(futureEvents.findById(eventId)).willReturn(futureEvent);

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, outcomeA)).isEqualTo(70);
        assertThat(probabilityOf(futureEvent, outcomeB)).isEqualTo(18);
        assertThat(probabilityOf(futureEvent, outcomeC)).isEqualTo(12);
        assertThat(futureEvent.sealBreach()).isFalse();
        verify(futureEvents, never()).append(any(), any());
    }

    @Test
    void resolve_correlatedCardSupersededByAnotherPlayersLaterShiftOnSameEvent_doesNotClobberIt() {
        // Regression: P1's PUSH applied first (preShiftSnapshot 50/30/20), then P2's SUPPRESS landed on the
        // same event afterward. CORRUPT targets P1. Restoring P1's stale snapshot would silently discard
        // P2's shift too — instead, since P1's card is no longer the event's last shift this round, CORRUPT
        // must have no effect.
        var eventId = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();
        var outcomeC = UUID.randomUUID();
        var futureEvent = draftedFutureEvent(eventId, outcomeA, 64, outcomeB, 10, outcomeC, 26);
        var p1PreShiftSnapshot = Map.of(outcomeA, 50, outcomeB, 30, outcomeC, 20);
        var p1Card = new PlayerCard(eventId, ShiftType.PUSH, null, outcomeA, 20, p1PreShiftSnapshot);
        // The event's actual last shift this round is P2's SUPPRESS, not P1's PUSH.
        var p2LastShift = new EventShift(
                ShiftType.SUPPRESS, null, outcomeB, -20, Map.of(outcomeA, 70, outcomeB, 18, outcomeC, 12));

        given(pendingCorrupt.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER)).willReturn(List.of(TARGET_PLAYER_ID));
        given(roundCardByPlayer.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, TARGET_PLAYER_ID))
                .willReturn(Optional.of(p1Card));
        given(eventLastShift.find(GAME_ID, ERA_NUMBER, ROUND_NUMBER, eventId)).willReturn(Optional.of(p2LastShift));

        handler.resolve(GAME_ID, ERA_NUMBER, ROUND_NUMBER);

        assertThat(probabilityOf(futureEvent, outcomeA)).isEqualTo(64);
        assertThat(probabilityOf(futureEvent, outcomeB)).isEqualTo(10);
        assertThat(probabilityOf(futureEvent, outcomeC)).isEqualTo(26);
        verify(futureEvents, never()).findById(any());
        verify(futureEvents, never()).append(any(), any());
    }

    private void then_noFutureEventInteractions() {
        verify(futureEvents, never()).findById(any());
        verify(futureEvents, never()).append(any(), any());
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
