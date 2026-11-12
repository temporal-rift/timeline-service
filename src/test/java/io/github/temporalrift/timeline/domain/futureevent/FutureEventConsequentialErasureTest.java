package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.temporalrift.timeline.domain.event.EraStateCleared;
import io.github.temporalrift.timeline.domain.event.EventStalled;
import io.github.temporalrift.timeline.domain.event.EventUnstalled;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeAnnihilated;
import io.github.temporalrift.timeline.domain.event.OutcomeSealed;
import io.github.temporalrift.timeline.domain.event.ProbabilityShifted;

class FutureEventConsequentialErasureTest {

    static final UUID GAME_ID = UUID.randomUUID();
    static final int ERA_NUMBER = 1;
    static final int FLOOR = 0;
    static final int CEILING = 90;

    final UUID id = UUID.randomUUID();
    final Outcome first = new Outcome(UUID.randomUUID(), "first", 40);
    final Outcome second = new Outcome(UUID.randomUUID(), "second", 40);
    final Outcome third = new Outcome(UUID.randomUUID(), "third", 20);

    @ParameterizedTest(name = "roll {0} -> winner {1}, consequential {2}")
    @CsvSource({"85, second, true", "10, first, false", "45, first, true", "39, first, false"})
    void liveErasure_judgesTheRealRollAgainstTheUndoneRedistribution(
            int roll, String expectedWinner, boolean consequential) {
        var event = replay(new FutureEventDrafted(id, List.of(first, second, third)));
        var annihilated = event.annihilateOutcome(third.outcomeId(), FLOOR, CEILING);
        assertThat(annihilated.outcomes()).extracting(Outcome::probability).containsExactly(50, 50, 0);

        var applied = event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(roll));

        assertThat(applied.winningOutcomeId()).isEqualTo(outcomeNamed(expectedWinner));
        assertThat(applied.consequentialErasures()).isEqualTo(consequential ? List.of(third.outcomeId()) : List.of());
    }

    @ParameterizedTest(name = "roll {0} -> winner {1}, consequential {2}")
    @CsvSource({"85, second, true", "10, first, false", "45, first, true", "39, first, false"})
    void replayedErasure_judgesTheSameAsTheLiveOne(int roll, String expectedWinner, boolean consequential) {
        var event = replay(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third))));

        var applied = event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(roll));

        assertThat(applied.winningOutcomeId()).isEqualTo(outcomeNamed(expectedWinner));
        assertThat(applied.consequentialErasures()).isEqualTo(consequential ? List.of(third.outcomeId()) : List.of());
    }

    @Test
    void laterShiftsAreKept_andTheCounterfactualClampsAtZero() {
        var a = new Outcome(UUID.randomUUID(), "a", 56);
        var b = new Outcome(UUID.randomUUID(), "b", 24);
        var c = new Outcome(UUID.randomUUID(), "c", 20);
        var event = replay(new FutureEventDrafted(id, List.of(a, b, c)));
        var annihilated = event.annihilateOutcome(c.outcomeId(), FLOOR, CEILING);
        assertThat(annihilated.outcomes()).extracting(Outcome::probability).containsExactly(70, 30, 0);
        var shifted = replay(
                new FutureEventDrafted(id, List.of(a, b, c)),
                annihilated,
                new ProbabilityShifted(id, List.of(at(a, 10), at(b, 90), erased(c))));

        var applied = shifted.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(85));

        // Real 10/90/0 selects b; counterfactual 10-14 -> 0, 90-6 = 84, 20 selects c at 85.
        assertThat(applied.winningOutcomeId()).isEqualTo(b.outcomeId());
        assertThat(applied.consequentialErasures()).containsExactly(c.outcomeId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 39, 45, 85, 99})
    void noErasure_listsNothing(int roll) {
        var event = replay(new FutureEventDrafted(id, List.of(first, second, third)));

        assertThat(event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(roll)).consequentialErasures())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 49, 50, 99})
    void weightlessErasure_listsNothing(int roll) {
        var empty = new Outcome(third.outcomeId(), "third", 0);
        var event = replay(new FutureEventDrafted(id, List.of(at(first, 50), at(second, 50), empty)));
        event.annihilateOutcome(empty.outcomeId(), FLOOR, CEILING);

        assertThat(event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(roll)).consequentialErasures())
                .isEmpty();
    }

    @Test
    void weightlessErasureThatRebalancesTheSurvivors_listsNothing() {
        var a = new Outcome(UUID.randomUUID(), "a", 95);
        var b = new Outcome(UUID.randomUUID(), "b", 5);
        var c = new Outcome(UUID.randomUUID(), "c", 0);
        var event = replay(new FutureEventDrafted(id, List.of(a, b, c)));
        var annihilated = event.annihilateOutcome(c.outcomeId(), FLOOR, CEILING);
        assertThat(annihilated.outcomes()).extracting(Outcome::probability).containsExactly(90, 10, 0);

        var applied = event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(92));

        // Restoring 95/5/0 would select a at 92, but c held nothing, so its erasure is never judged.
        assertThat(applied.winningOutcomeId()).isEqualTo(b.outcomeId());
        assertThat(applied.consequentialErasures()).isEmpty();
    }

    @Test
    void repeatedAnnihilateOnAnErasedOutcome_keepsTheOriginalErasure() {
        var event = replay(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third))));
        var repeated = event.annihilateOutcome(third.outcomeId(), FLOOR, CEILING);
        var replayed = replay(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third))),
                repeated);

        assertThat(event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(85)).consequentialErasures())
                .containsExactly(third.outcomeId());
        assertThat(replayed.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(85)).consequentialErasures())
                .containsExactly(third.outcomeId());
    }

    @Test
    void erasureSurvivesSealsAndStalls() {
        var event = replay(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third))),
                new OutcomeSealed(id, List.of(sealed(at(first, 50)), at(second, 50), erased(third))),
                new EventStalled(id),
                new EventUnstalled(id));

        assertThat(event.resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(85)).consequentialErasures())
                .containsExactly(third.outcomeId());
    }

    @Test
    void carriedEvent_doesNotJudgeTheErasureOfAnEarlierEra() {
        var history = new ArrayList<Object>(List.of(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third))),
                new EraStateCleared(id, List.of(at(first, 50), at(second, 50), at(third, 0)))));
        var live = replay(history.subList(0, 2).toArray());
        live.clearEraState();

        assertThat(replay(history.toArray())
                        .resolve(GAME_ID, ERA_NUMBER + 1, FixedDraw.of(85))
                        .consequentialErasures())
                .isEmpty();
        assertThat(live.resolve(GAME_ID, ERA_NUMBER + 1, FixedDraw.of(85)).consequentialErasures())
                .isEmpty();
    }

    @Test
    void cascadeReErasureAfterCarry_isJudgedInItsOwnEra() {
        var event = replay(
                new FutureEventDrafted(id, List.of(first, second, third)),
                new EraStateCleared(id, List.of(first, second, third)));
        event.annihilateOutcome(third.outcomeId(), FLOOR, CEILING);

        var applied = event.resolve(GAME_ID, ERA_NUMBER + 1, FixedDraw.of(85));

        assertThat(applied.winningOutcomeId()).isEqualTo(second.outcomeId());
        assertThat(applied.consequentialErasures()).containsExactly(third.outcomeId());
    }

    @Test
    void sameHistoryAndRoll_produceTheSameList() {
        Object[] history = {
            new FutureEventDrafted(id, List.of(first, second, third)),
            new OutcomeAnnihilated(id, List.of(at(first, 50), at(second, 50), erased(third)))
        };

        var once = replay(history).resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(45));
        var again = replay(history).resolve(GAME_ID, ERA_NUMBER, FixedDraw.of(45));

        assertThat(again).isEqualTo(once);
    }

    private FutureEvent replay(Object... history) {
        return FutureEvent.replay(id, List.of(history));
    }

    private UUID outcomeNamed(String name) {
        return switch (name) {
            case "first" -> first.outcomeId();
            case "second" -> second.outcomeId();
            default -> third.outcomeId();
        };
    }

    private static Outcome at(Outcome outcome, int probability) {
        return new Outcome(outcome.outcomeId(), outcome.description(), probability, outcome.sealed(), false);
    }

    private static Outcome erased(Outcome outcome) {
        return new Outcome(outcome.outcomeId(), outcome.description(), 0, false, true);
    }

    private static Outcome sealed(Outcome outcome) {
        return new Outcome(outcome.outcomeId(), outcome.description(), outcome.probability(), true, false);
    }
}
