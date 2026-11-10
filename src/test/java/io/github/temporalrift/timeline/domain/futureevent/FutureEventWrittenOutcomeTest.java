package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.EraStateCleared;
import io.github.temporalrift.timeline.domain.event.FutureEventDrafted;
import io.github.temporalrift.timeline.domain.event.OutcomeWritten;

class FutureEventWrittenOutcomeTest {

    private static final int FLOOR = 0;
    private static final int CEILING = 90;

    private final UUID id = UUID.randomUUID();
    private final UUID o1 = UUID.randomUUID();
    private final UUID o2 = UUID.randomUUID();
    private final UUID o3 = UUID.randomUUID();

    @Test
    @DisplayName("the first writing stands and a later one records nothing")
    void writeOutcome_firstWritingStands() {
        var event = event(50, 30, 20);

        var first = event.writeOutcome(o3, 1);
        var second = event.writeOutcome(o2, 2);

        assertThat(first).contains(new OutcomeWritten(id, o3, 1));
        assertThat(second).isEmpty();
        assertThat(event.writtenOutcome()).contains(new WrittenOutcome(o3, 1));
    }

    @Test
    @DisplayName("writing an unknown outcome is refused")
    void writeOutcome_unknownOutcome_throws() {
        var event = event(50, 30, 20);
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> event.writeOutcome(unknown, 1)).isInstanceOf(UnknownOutcomeException.class);
        assertThat(event.writtenOutcome()).isEmpty();
    }

    @Test
    @DisplayName("a resolved event cannot be written")
    void writeOutcome_resolved_throws() {
        var event = event(50, 30, 20);
        event.resolve(UUID.randomUUID(), 1, RandomGenerator.of("L64X128MixRandom"));

        assertThatThrownBy(() -> event.writeOutcome(o1, 1)).isInstanceOf(FutureEventAlreadyResolvedException.class);
    }

    @Test
    @DisplayName("replaying the stream restores the writing, and a carry clears it")
    void replay_restoresAndClearsTheWriting() {
        var drafted = new FutureEventDrafted(id, outcomes(50, 30, 20));
        var written = new OutcomeWritten(id, o3, 2);

        var replayed = FutureEvent.replay(id, List.of(drafted, written));
        var carried = FutureEvent.replay(id, List.of(drafted, written, new EraStateCleared(id, outcomes(50, 30, 20))));

        assertThat(replayed.writtenOutcome()).contains(new WrittenOutcome(o3, 2));
        assertThat(carried.writtenOutcome()).isEmpty();
    }

    @Test
    @DisplayName("a writing replayed before the draft is refused")
    void replay_writingBeforeDraft_throws() {
        List<Object> history = List.of(new OutcomeWritten(id, o3, 1));

        assertThatThrownBy(() -> FutureEvent.replay(id, history)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("clearing era state forgets the writing, so the carried event may be written anew")
    void clearEraState_forgetsTheWriting() {
        var event = event(50, 30, 20);
        event.writeOutcome(o3, 1);

        event.clearEraState();

        assertThat(event.writtenOutcome()).isEmpty();
        assertThat(event.writeOutcome(o2, 1)).contains(new OutcomeWritten(id, o2, 1));
    }

    @Test
    @DisplayName("50/30/20 written on O3, erasing O1 gives 0/30/70")
    void routed_writtenOutcomeTakesTheErasedWeight() {
        var event = event(50, 30, 20);
        event.writeOutcome(o3, 1);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 30, 70);
    }

    @Test
    @DisplayName("a writing in the erasure's own round counts: 50/30/20 written on O2 gives 0/80/20")
    void routed_sameRoundWritingCounts() {
        var event = event(50, 30, 20);
        event.writeOutcome(o2, 2);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 80, 20);
    }

    @Test
    @DisplayName("the ceiling caps the written outcome: 70/25/5 written on O2 gives 0/90/10")
    void routed_ceilingCapsTheWrittenOutcome() {
        var event = event(70, 25, 5);
        event.writeOutcome(o2, 1);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 90, 10);
    }

    @Test
    @DisplayName("a writing after the erasure's round does not reach back")
    void routed_laterWriting_isProportional() {
        var event = event(50, 30, 20);
        event.writeOutcome(o3, 3);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 60, 40);
    }

    @Test
    @DisplayName("without a writing, routing falls back to proportional")
    void routed_noWriting_isProportional() {
        var event = event(50, 30, 20);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 60, 40);
    }

    @Test
    @DisplayName("erasing the written outcome itself is proportional")
    void routed_writtenOutcomeErased_isProportional() {
        var event = event(50, 30, 20);
        event.writeOutcome(o1, 1);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 60, 40);
    }

    @Test
    @DisplayName("a sealed written outcome keeps its weight: 50/30/20 sealed and written on O3 gives 0/80/20")
    void routed_sealedWrittenOutcome_keepsItsWeight() {
        var event = event(50, 30, 20);
        event.sealOutcome(o3);
        event.writeOutcome(o3, 1);

        erase(event, ErasureRedistribution.writtenOutcomeFirst(2), o1);

        assertThat(weights(event)).containsExactly(0, 80, 20);
    }

    @Test
    @DisplayName("the proportional policy ignores the writing")
    void proportional_ignoresTheWriting() {
        var event = event(50, 30, 20);
        event.writeOutcome(o3, 1);

        erase(event, ErasureRedistribution.proportional(), o1);

        assertThat(weights(event)).containsExactly(0, 60, 40);
    }

    @Test
    @DisplayName("routing never changes whether an erasure is accepted, and accepted results stay in bounds")
    void routing_neverFlipsAcceptance() {
        var cases = gridCases();

        cases.forEach(this::assertSameAcceptance);

        assertThat(cases).hasSizeGreaterThan(10_000);
    }

    /** Weights in steps of 5, five valid floor/ceiling pairs, no or one sealed outcome, every target and writing. */
    private List<GridCase> gridCases() {
        int[][] bounds = {{0, 90}, {5, 90}, {10, 80}, {0, 50}, {20, 60}};
        var ids = List.of(o1, o2, o3);
        var weights = new ArrayList<int[]>();
        for (int a = 0; a <= 100; a += 5) {
            for (int b = 0; a + b <= 100; b += 5) {
                weights.add(new int[] {a, b, 100 - a - b});
            }
        }
        var cases = new ArrayList<GridCase>();
        for (var bound : bounds) {
            for (var weight : weights) {
                for (int sealed = -1; sealed < 3; sealed++) {
                    int sealedIndex = sealed;
                    ids.forEach(target -> ids.forEach(written ->
                            cases.add(new GridCase(bound[0], bound[1], weight, sealedIndex, target, written))));
                }
            }
        }
        return cases;
    }

    private void assertSameAcceptance(GridCase grid) {
        var routed = sealedEvent(grid.weights(), grid.sealedIndex());
        routed.writeOutcome(grid.written(), 1);
        var proportional = sealedEvent(grid.weights(), grid.sealedIndex());
        var targets = Set.of(grid.target());
        var routing = ErasureRedistribution.writtenOutcomeFirst(2);

        boolean routedAccepted = routed.canAnnihilate(targets, grid.floor(), grid.ceiling(), routing);
        boolean proportionalAccepted =
                proportional.canAnnihilate(targets, grid.floor(), grid.ceiling(), ErasureRedistribution.proportional());

        assertThat(routedAccepted).as("%s", grid).isEqualTo(proportionalAccepted);
        if (routedAccepted) {
            routed.annihilateOutcomes(targets, grid.floor(), grid.ceiling(), routing);
            assertWithinBounds(routed, grid.floor(), grid.ceiling());
        }
    }

    private record GridCase(int floor, int ceiling, int[] weights, int sealedIndex, UUID target, UUID written) {
        @Override
        public String toString() {
            return "%d/%d/%d sealed=%d floor=%d ceiling=%d"
                    .formatted(weights[0], weights[1], weights[2], sealedIndex, floor, ceiling);
        }
    }

    private static void assertWithinBounds(FutureEvent event, int floor, int ceiling) {
        assertThat(event.outcomes().stream().mapToInt(Outcome::probability).sum())
                .isEqualTo(100);
        event.outcomes().stream()
                .filter(o -> !o.annihilated() && !o.sealed())
                .forEach(o -> assertThat(o.probability()).isBetween(floor, ceiling));
    }

    private void erase(FutureEvent event, ErasureRedistribution redistribution, UUID target) {
        assertThat(event.canAnnihilate(Set.of(target), FLOOR, CEILING, redistribution))
                .isTrue();
        event.annihilateOutcomes(Set.of(target), FLOOR, CEILING, redistribution);
    }

    private FutureEvent event(int a, int b, int c) {
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, outcomes(a, b, c))));
    }

    private FutureEvent sealedEvent(int[] weights, int sealedIndex) {
        var outcomes = new ArrayList<>(outcomes(weights[0], weights[1], weights[2]));
        if (sealedIndex >= 0) {
            var o = outcomes.get(sealedIndex);
            outcomes.set(sealedIndex, new Outcome(o.outcomeId(), o.description(), o.probability(), true, false));
        }
        return FutureEvent.replay(id, List.of(new FutureEventDrafted(id, outcomes)));
    }

    private List<Outcome> outcomes(int a, int b, int c) {
        return List.of(new Outcome(o1, "o1", a), new Outcome(o2, "o2", b), new Outcome(o3, "o3", c));
    }

    private List<Integer> weights(FutureEvent event) {
        return event.outcomes().stream().map(Outcome::probability).toList();
    }
}
