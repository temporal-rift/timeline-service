package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ParadoxDetectorTest {

    @Test
    void detect_annihilatedOutcomeStrictlyHighest_reportsImpossibleErasure() {
        var annihilatedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 50, false, true),
                new Outcome(UUID.randomUUID(), "second", 30),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactly(annihilatedId);
    }

    @Test
    void detect_annihilatedOutcomeTiedWithHighestNonAnnihilated_reportsImpossibleErasure() {
        // Boundary case: >= (not just >) triggers the paradox.
        var annihilatedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 40, false, true),
                new Outcome(UUID.randomUUID(), "tied", 40),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactly(annihilatedId);
    }

    @Test
    void detect_annihilatedOutcomeBelowHighestNonAnnihilated_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilated", 30, false, true),
                new Outcome(UUID.randomUUID(), "highest", 50),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes);

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_noAnnihilatedOutcome_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 45),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes);

        assertThat(paradoxes).isEmpty();
    }
}
