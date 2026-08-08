package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.EventStalled;
import io.github.temporalrift.timeline.domain.event.EventUnstalled;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeAnnihilated;
import io.github.temporalrift.timeline.domain.event.OutcomeApplied;
import io.github.temporalrift.timeline.domain.event.OutcomeSealed;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;
import io.github.temporalrift.timeline.domain.event.SealBreachRecorded;

class FutureEventTest {

    static final UUID GAME_ID = UUID.randomUUID();
    static final int ERA_NUMBER = 1;

    @Test
    void resolve_highestProbability_wins() {
        var id = UUID.randomUUID();
        var winner = new Outcome(UUID.randomUUID(), "winner", 45);
        var second = new Outcome(UUID.randomUUID(), "second", 35);
        var third = new Outcome(UUID.randomUUID(), "third", 20);
        var event = drafted(id, winner, second, third);

        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(winner.outcomeId());
        assertThat(outcomeApplied.gameId()).isEqualTo(GAME_ID);
        assertThat(outcomeApplied.eraNumber()).isEqualTo(ERA_NUMBER);
        assertThat(outcomeApplied.eventId()).isEqualTo(id);
        assertThat(event.resolved()).isTrue();
    }

    @Test
    void resolve_tiedProbabilities_smallestOutcomeIdWins() {
        var id = UUID.randomUUID();
        var lower = new Outcome(UUID.fromString("00000000-0000-0000-0000-000000000001"), "lower", 40);
        var higher = new Outcome(UUID.fromString("00000000-0000-0000-0000-000000000002"), "higher", 40);
        var third = new Outcome(UUID.randomUUID(), "third", 20);
        var event = drafted(id, higher, lower, third);

        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(lower.outcomeId());
    }

    @Test
    void resolve_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(() -> event.resolve(GAME_ID, ERA_NUMBER))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void replay_emptyHistory_throwsNotFound() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> FutureEvent.replay(id, List.of())).isInstanceOf(FutureEventNotFoundException.class);
    }

    @Test
    void replay_outcomeAppliedBeforeDrafted_throwsIllegalState() {
        var id = UUID.randomUUID();
        var outcomeApplied = new OutcomeApplied(GAME_ID, ERA_NUMBER, id, UUID.randomUUID(), List.of());
        List<Object> history = List.of(outcomeApplied);

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_probabilityShiftedBeforeDrafted_throwsIllegalState() {
        var id = UUID.randomUUID();
        var shifted = new ProbabilityShifted(id, List.of());
        List<Object> history = List.of(shifted);

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_probabilityShiftedAfterOutcomeApplied_throwsIllegalState() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var drafted = new FutureEventDrafted(id, List.of(outcome));
        var applied = new OutcomeApplied(GAME_ID, ERA_NUMBER, id, outcome.outcomeId(), List.of(outcome));
        var shifted = new ProbabilityShifted(id, List.of(outcome));
        List<Object> history = List.of(drafted, applied, shifted);

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_duplicateFutureEventDrafted_throwsIllegalState() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var drafted = new FutureEventDrafted(id, List.of(outcome));
        List<Object> history = List.of(drafted, drafted);

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replay_draftedThenApplied_reconstructsResolvedState() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var drafted = new FutureEventDrafted(id, List.of(outcome));
        var applied = new OutcomeApplied(GAME_ID, ERA_NUMBER, id, outcome.outcomeId(), List.of(outcome));

        var event = FutureEvent.replay(id, List.of(drafted, applied));

        assertThat(event.resolved()).isTrue();
        assertThatThrownBy(() -> event.resolve(GAME_ID, ERA_NUMBER))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    private static FutureEvent drafted(UUID id, Outcome... outcomes) {
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, List.of(outcomes))));
    }

    @Test
    void applyShift_push_increasesTargetAndRedistributesProportionally() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(byId(event, a.outcomeId())).isEqualTo(70);
        assertThat(byId(event, b.outcomeId())).isEqualTo(18);
        assertThat(byId(event, c.outcomeId())).isEqualTo(12);
        assertThat(sum(event)).isEqualTo(100);
    }

    @Test
    void applyShift_suppress_decreasesTargetAndRedistributesProportionally() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Suppress(b.outcomeId()), -20, 0, 90);

        assertThat(byId(event, b.outcomeId())).isEqualTo(10);
        assertThat(byId(event, a.outcomeId())).isEqualTo(64);
        assertThat(byId(event, c.outcomeId())).isEqualTo(26);
        assertThat(sum(event)).isEqualTo(100);
    }

    @Test
    void applyShift_push_clampedAtCeiling() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 85);
        var b = new Outcome(UUID.randomUUID(), "b", 10);
        var c = new Outcome(UUID.randomUUID(), "c", 5);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(byId(event, a.outcomeId())).isEqualTo(90);
        assertThat(byId(event, b.outcomeId())).isEqualTo(7);
        assertThat(byId(event, c.outcomeId())).isEqualTo(3);
        assertThat(sum(event)).isEqualTo(100);
    }

    @Test
    void applyShift_suppress_clampedAtFloor() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 15);
        var b = new Outcome(UUID.randomUUID(), "b", 45);
        var c = new Outcome(UUID.randomUUID(), "c", 40);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Suppress(a.outcomeId()), -20, 0, 90);

        assertThat(byId(event, a.outcomeId())).isZero();
        assertThat(byId(event, b.outcomeId())).isEqualTo(53);
        assertThat(byId(event, c.outcomeId())).isEqualTo(47);
        assertThat(sum(event)).isEqualTo(100);
    }

    @Test
    void applyShift_push_noOpWhenTargetAlreadyAtCeiling() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 90);
        var b = new Outcome(UUID.randomUUID(), "b", 5);
        var c = new Outcome(UUID.randomUUID(), "c", 5);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(event.outcomes()).containsExactly(a, b, c);
    }

    @Test
    void applyShift_redistribution_clampExchangeEdgeCase() {
        // Target is suppressed to the floor while one of the other two outcomes is already at the
        // ceiling — its naive proportional share would push it past 90, so the exchange must redirect
        // the overflow to the third outcome (design.md Risks).
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 5);
        var b = new Outcome(UUID.randomUUID(), "b", 90);
        var c = new Outcome(UUID.randomUUID(), "c", 5);
        var event = drafted(id, a, b, c);

        event.applyShift(new ProbabilityShift.Suppress(a.outcomeId()), -20, 0, 90);

        assertThat(byId(event, a.outcomeId())).isZero();
        assertThat(byId(event, b.outcomeId())).isEqualTo(90);
        assertThat(byId(event, c.outcomeId())).isEqualTo(10);
        assertThat(sum(event)).isEqualTo(100);
    }

    @Test
    void applyShift_swing_movesMagnitudeLeavingThirdOutcomeUntouched() {
        var id = UUID.randomUUID();
        var source = new Outcome(UUID.randomUUID(), "source", 50);
        var target = new Outcome(UUID.randomUUID(), "target", 30);
        var other = new Outcome(UUID.randomUUID(), "other", 20);
        var event = drafted(id, source, target, other);

        event.applyShift(new ProbabilityShift.Swing(source.outcomeId(), target.outcomeId()), 30, 0, 90);

        assertThat(byId(event, source.outcomeId())).isEqualTo(20);
        assertThat(byId(event, target.outcomeId())).isEqualTo(60);
        assertThat(byId(event, other.outcomeId())).isEqualTo(20);
    }

    @Test
    void applyShift_swing_clampedBySourceFloor() {
        var id = UUID.randomUUID();
        var source = new Outcome(UUID.randomUUID(), "source", 10);
        var target = new Outcome(UUID.randomUUID(), "target", 30);
        var other = new Outcome(UUID.randomUUID(), "other", 60);
        var event = drafted(id, source, target, other);

        event.applyShift(new ProbabilityShift.Swing(source.outcomeId(), target.outcomeId()), 30, 0, 90);

        assertThat(byId(event, source.outcomeId())).isZero();
        assertThat(byId(event, target.outcomeId())).isEqualTo(40);
        assertThat(byId(event, other.outcomeId())).isEqualTo(60);
    }

    @Test
    void applyShift_swing_clampedByTargetCeiling() {
        var id = UUID.randomUUID();
        var source = new Outcome(UUID.randomUUID(), "source", 10);
        var target = new Outcome(UUID.randomUUID(), "target", 85);
        var other = new Outcome(UUID.randomUUID(), "other", 5);
        var event = drafted(id, source, target, other);

        event.applyShift(new ProbabilityShift.Swing(source.outcomeId(), target.outcomeId()), 30, 0, 90);

        assertThat(byId(event, source.outcomeId())).isEqualTo(5);
        assertThat(byId(event, target.outcomeId())).isEqualTo(90);
        assertThat(byId(event, other.outcomeId())).isEqualTo(5);
    }

    @Test
    void applyShift_swing_sameSourceAndTarget_throws() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        var shift = new ProbabilityShift.Swing(a.outcomeId(), a.outcomeId());

        assertThatThrownBy(() -> event.applyShift(shift, 30, 0, 90)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyShift_swing_nullSourceOutcomeId_throwsUnknownOutcomeNotNpe() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        assertThatThrownBy(() -> event.applyShift(new ProbabilityShift.Swing(null, a.outcomeId()), 30, 0, 90))
                .isInstanceOf(UnknownOutcomeException.class);
    }

    @Test
    void applyShift_unknownOutcomeId_throws() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        assertThatThrownBy(() -> event.applyShift(new ProbabilityShift.Push(UUID.randomUUID()), 20, 0, 90))
                .isInstanceOf(UnknownOutcomeException.class);
    }

    @Test
    void applyShift_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(() -> event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void replay_probabilityShiftedFoldsOutcomes() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var drafted = new FutureEventDrafted(id, List.of(a, b, c));
        var shifted = new ProbabilityShifted(
                id, List.of(new Outcome(a.outcomeId(), "a", 70), new Outcome(b.outcomeId(), "b", 18), c));

        var event = FutureEvent.replay(id, List.of(drafted, shifted));

        assertThat(byId(event, a.outcomeId())).isEqualTo(70);
        assertThat(event.resolved()).isFalse();
    }

    @Test
    void markStalled_thenResolve_throwsStalled() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));

        event.markStalled();

        assertThat(event.stalled()).isTrue();
        assertThatThrownBy(() -> event.resolve(GAME_ID, ERA_NUMBER)).isInstanceOf(FutureEventStalledException.class);
    }

    @Test
    void markStalled_thenClearStalled_resolvesNormally() {
        var id = UUID.randomUUID();
        var winner = new Outcome(UUID.randomUUID(), "winner", 100);
        var event = drafted(id, winner);

        event.markStalled();
        event.clearStalled();

        assertThat(event.stalled()).isFalse();
        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);
        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(winner.outcomeId());
    }

    @Test
    void markStalled_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(event::markStalled).isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void replay_eventStalledThenEventUnstalled_reconstructsUnstalledState() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var drafted = new FutureEventDrafted(id, List.of(outcome));

        var event = FutureEvent.replay(id, List.of(drafted, new EventStalled(id), new EventUnstalled(id)));

        assertThat(event.stalled()).isFalse();
        assertThat(event.resolved()).isFalse();
    }

    @Test
    void replay_eventStalledBeforeDrafted_throwsIllegalState() {
        var id = UUID.randomUUID();
        List<Object> history = List.of(new EventStalled(id));

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyShift_restore_setsExactProbabilitiesIgnoringMagnitude() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        event.applyShift(
                new ProbabilityShift.Restore(Map.of(a.outcomeId(), 50, b.outcomeId(), 30, c.outcomeId(), 20)),
                999,
                0,
                90);

        assertThat(byId(event, a.outcomeId())).isEqualTo(50);
        assertThat(byId(event, b.outcomeId())).isEqualTo(30);
        assertThat(byId(event, c.outcomeId())).isEqualTo(20);
    }

    @Test
    void applyShift_restore_conflictingWithNowSealedOutcome_declinesAndSetsSealBreach() {
        // The snapshot predates a SEAL cast on b afterward, at a different value than the snapshot holds.
        // Restoring verbatim would silently overwrite b's frozen probability, so the whole restore must be
        // declined rather than partially rebuilding a/c around a value it isn't allowed to touch, and
        // recorded as a breach — the same signal PUSH/SUPPRESS/SWING record when a seal blocks them.
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 70);
        var b = new Outcome(UUID.randomUUID(), "b", 18);
        var c = new Outcome(UUID.randomUUID(), "c", 12);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());

        var result = event.applyShift(
                new ProbabilityShift.Restore(Map.of(a.outcomeId(), 50, b.outcomeId(), 30, c.outcomeId(), 20)),
                0,
                0,
                90);

        assertThat(result).isInstanceOf(SealBreachRecorded.class);
        assertThat(byId(event, a.outcomeId())).isEqualTo(70);
        assertThat(byId(event, b.outcomeId())).isEqualTo(18);
        assertThat(byId(event, c.outcomeId())).isEqualTo(12);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_restore_consistentWithSealedOutcomesValue_stillAppliesWithoutBreach() {
        // b is sealed but the snapshot's value for b matches its current (frozen) value exactly, so
        // restoring a/c around it is safe — this is a genuine no-op for b, not a blocked attempt.
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 70);
        var b = new Outcome(UUID.randomUUID(), "b", 18);
        var c = new Outcome(UUID.randomUUID(), "c", 12);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());

        var result = event.applyShift(
                new ProbabilityShift.Restore(Map.of(a.outcomeId(), 50, b.outcomeId(), 18, c.outcomeId(), 32)),
                0,
                0,
                90);

        assertThat(result).isInstanceOf(ProbabilityShifted.class);
        assertThat(byId(event, a.outcomeId())).isEqualTo(50);
        assertThat(byId(event, b.outcomeId())).isEqualTo(18);
        assertThat(byId(event, c.outcomeId())).isEqualTo(32);
        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(b.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .sealed())
                .isTrue();
        assertThat(event.sealBreach()).isFalse();
    }

    @Test
    void resolve_annihilatedOutcome_excludedEvenAtHighestProbability() {
        var id = UUID.randomUUID();
        var highest = new Outcome(UUID.randomUUID(), "highest", 45);
        var second = new Outcome(UUID.randomUUID(), "second", 35);
        var third = new Outcome(UUID.randomUUID(), "third", 20);
        var event = drafted(id, highest, second, third);
        event.annihilateOutcome(highest.outcomeId());

        var outcomeApplied = event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(outcomeApplied.winningOutcomeId()).isEqualTo(second.outcomeId());
    }

    @Test
    void annihilateOutcome_flagSurvivesOutcomeApplied() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        event.annihilateOutcome(b.outcomeId());
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(b.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .annihilated())
                .isTrue();
    }

    @Test
    void annihilateOutcome_unknownOutcomeId_throws() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));

        assertThatThrownBy(() -> event.annihilateOutcome(UUID.randomUUID()))
                .isInstanceOf(UnknownOutcomeException.class);
    }

    @Test
    void annihilateOutcome_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var event = drafted(id, outcome);
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(() -> event.annihilateOutcome(outcome.outcomeId()))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void sealOutcome_marksOutcomeSealed() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);

        event.sealOutcome(a.outcomeId());

        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(a.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .sealed())
                .isTrue();
        assertThat(event.sealBreach()).isFalse();
    }

    @Test
    void sealOutcome_unknownOutcomeId_throws() {
        var id = UUID.randomUUID();
        var event = drafted(id, new Outcome(UUID.randomUUID(), "only", 100));

        assertThatThrownBy(() -> event.sealOutcome(UUID.randomUUID())).isInstanceOf(UnknownOutcomeException.class);
    }

    @Test
    void sealOutcome_alreadyResolved_throws() {
        var id = UUID.randomUUID();
        var outcome = new Outcome(UUID.randomUUID(), "only", 100);
        var event = drafted(id, outcome);
        event.resolve(GAME_ID, ERA_NUMBER);

        assertThatThrownBy(() -> event.sealOutcome(outcome.outcomeId()))
                .isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    void applyShift_push_targetSealed_setsSealBreachWithoutChangingProbability() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.sealOutcome(a.outcomeId());

        var result = event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(result).isInstanceOf(SealBreachRecorded.class);
        assertThat(byId(event, a.outcomeId())).isEqualTo(50);
        assertThat(byId(event, b.outcomeId())).isEqualTo(30);
        assertThat(byId(event, c.outcomeId())).isEqualTo(20);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_suppress_targetSealed_setsSealBreachWithoutChangingProbability() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());

        event.applyShift(new ProbabilityShift.Suppress(b.outcomeId()), -20, 0, 90);

        assertThat(byId(event, b.outcomeId())).isEqualTo(30);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_swing_sourceSealed_setsSealBreachWithoutChangingProbability() {
        var id = UUID.randomUUID();
        var source = new Outcome(UUID.randomUUID(), "source", 50);
        var target = new Outcome(UUID.randomUUID(), "target", 30);
        var other = new Outcome(UUID.randomUUID(), "other", 20);
        var event = drafted(id, source, target, other);
        event.sealOutcome(source.outcomeId());

        event.applyShift(new ProbabilityShift.Swing(source.outcomeId(), target.outcomeId()), 30, 0, 90);

        assertThat(byId(event, source.outcomeId())).isEqualTo(50);
        assertThat(byId(event, target.outcomeId())).isEqualTo(30);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_swing_targetSealed_setsSealBreachWithoutChangingProbability() {
        var id = UUID.randomUUID();
        var source = new Outcome(UUID.randomUUID(), "source", 50);
        var target = new Outcome(UUID.randomUUID(), "target", 30);
        var other = new Outcome(UUID.randomUUID(), "other", 20);
        var event = drafted(id, source, target, other);
        event.sealOutcome(target.outcomeId());

        event.applyShift(new ProbabilityShift.Swing(source.outcomeId(), target.outcomeId()), 30, 0, 90);

        assertThat(byId(event, source.outcomeId())).isEqualTo(50);
        assertThat(byId(event, target.outcomeId())).isEqualTo(30);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_push_oneOfTheOtherOutcomesSealed_freeOutcomeAbsorbsAllRedistribution() {
        // Regression: PUSH's proportional redistribution must not touch a sealed outcome even when it is
        // neither the shift's target nor source — b is sealed here, only c may absorb a's movement.
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());

        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(byId(event, a.outcomeId())).isEqualTo(70);
        assertThat(byId(event, b.outcomeId())).isEqualTo(30);
        assertThat(byId(event, c.outcomeId())).isEqualTo(0);
        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(b.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .sealed())
                .isTrue();
        assertThat(event.sealBreach()).isFalse();
    }

    @Test
    void applyShift_push_bothOtherOutcomesSealed_setsSealBreachWithoutChangingProbability() {
        // Neither of the two non-target outcomes can absorb the redistribution, so the PUSH cannot apply
        // at all without touching a sealed outcome — this is itself a breach, on the sealed neighbors.
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());
        event.sealOutcome(c.outcomeId());

        var result = event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(result).isInstanceOf(SealBreachRecorded.class);
        assertThat(byId(event, a.outcomeId())).isEqualTo(50);
        assertThat(byId(event, b.outcomeId())).isEqualTo(30);
        assertThat(byId(event, c.outcomeId())).isEqualTo(20);
        assertThat(event.sealBreach()).isTrue();
    }

    @Test
    void applyShift_unaffectedOutcomeSealFlagSurvivesAnUnrelatedShift() {
        // Regression: replaceProbabilities must preserve sealed/annihilated flags on outcomes it rewrites
        // the probability of, not just the outcome the shift directly targeted.
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = drafted(id, a, b, c);
        event.sealOutcome(b.outcomeId());

        event.applyShift(new ProbabilityShift.Push(a.outcomeId()), 20, 0, 90);

        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(b.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .sealed())
                .isTrue();
        assertThat(event.sealBreach()).isFalse();
    }

    @Test
    void replay_reconstructsSealedAnnihilatedAndSealBreachState() {
        var id = UUID.randomUUID();
        var a = new Outcome(UUID.randomUUID(), "a", 50);
        var b = new Outcome(UUID.randomUUID(), "b", 30);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var drafted = new FutureEventDrafted(id, List.of(a, b, c));
        var sealed = new OutcomeSealed(id, List.of(new Outcome(a.outcomeId(), "a", 50, true, false), b, c));
        var breach = new SealBreachRecorded(id);
        var annihilated = new OutcomeAnnihilated(
                id,
                List.of(
                        new Outcome(a.outcomeId(), "a", 50, true, false),
                        new Outcome(b.outcomeId(), "b", 30, false, true),
                        c));

        var event = FutureEvent.replay(id, List.of(drafted, sealed, breach, annihilated));

        assertThat(byId(event, a.outcomeId())).isEqualTo(50);
        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(a.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .sealed())
                .isTrue();
        assertThat(event.outcomes().stream()
                        .filter(o -> o.outcomeId().equals(b.outcomeId()))
                        .findFirst()
                        .orElseThrow()
                        .annihilated())
                .isTrue();
        assertThat(event.sealBreach()).isTrue();
        assertThat(event.resolved()).isFalse();
    }

    private static int byId(FutureEvent event, UUID outcomeId) {
        return event.outcomes().stream()
                .filter(o -> o.outcomeId().equals(outcomeId))
                .findFirst()
                .orElseThrow()
                .probability();
    }

    private static int sum(FutureEvent event) {
        return event.outcomes().stream().mapToInt(Outcome::probability).sum();
    }
}
