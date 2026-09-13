package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

class ParadoxDetectorTest {

    @Test
    void detect_annihilatedOutcomeStrictlyHighest_reportsImpossibleErasure() {
        var annihilatedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 50, false, true),
                new Outcome(UUID.randomUUID(), "second", 30),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

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

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactly(annihilatedId);
    }

    @Test
    void detect_annihilatedOutcomeBelowHighestNonAnnihilated_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilated", 30, false, true),
                new Outcome(UUID.randomUUID(), "highest", 50),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_noAnnihilatedOutcome_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 45),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_twoNonAnnihilatedOutcomesTiedAtHighest_reportsDeadHeat() {
        var firstTiedId = UUID.randomUUID();
        var secondTiedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstTiedId, "first", 40),
                new Outcome(secondTiedId, "second", 40),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.DEAD_HEAT);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactlyInAnyOrder(firstTiedId, secondTiedId);
    }

    @Test
    void detect_threeWayTieAtHighest_reportsDeadHeatWithAllThree() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var thirdId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstId, "first", 30),
                new Outcome(secondId, "second", 30),
                new Outcome(thirdId, "third", 30));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.DEAD_HEAT);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactlyInAnyOrder(firstId, secondId, thirdId);
    }

    @Test
    void detect_tieBelowHighestProbability_reportsNoDeadHeat() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "highest", 40),
                new Outcome(UUID.randomUUID(), "tiedSecond1", 30),
                new Outcome(UUID.randomUUID(), "tiedSecond2", 30));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_tieBetweenAnnihilatedAndNonAnnihilated_reportsNoDeadHeat() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilated", 40, false, true),
                new Outcome(UUID.randomUUID(), "nonAnnihilated", 40),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        // IMPOSSIBLE_ERASURE fires (annihilated >= every non-annihilated), but not DEAD_HEAT — only one
        // non-annihilated outcome holds the highest non-annihilated probability.
        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
    }

    @Test
    void detect_sealBreachSet_reportsSealBreachWithSealedOutcomeIds() {
        var sealedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(sealedId, "sealed", 40, true, false),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(outcomes, true);

        assertThat(paradoxes).hasSize(1);
        assertThat(paradoxes.getFirst().type()).isEqualTo(ParadoxType.SEAL_BREACH);
        assertThat(paradoxes.getFirst().affectedOutcomeIds()).containsExactly(sealedId);
    }

    @Test
    void detect_sealBreachNotSet_reportsNoSealBreach() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "sealed", 40, true, false),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(outcomes, false);

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_conflictingChains_reportsChainConflict() {
        var eventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstOutcomeId, "first", 40),
                new Outcome(secondOutcomeId, "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes,
                false,
                eventId,
                List.of(chainLinking(eventId, firstOutcomeId), chainLinking(eventId, secondOutcomeId)));

        assertThat(paradoxes)
                .filteredOn(p -> p.type() == ParadoxType.CHAIN_CONFLICT)
                .singleElement()
                .satisfies(p ->
                        assertThat(p.affectedOutcomeIds()).containsExactlyInAnyOrder(firstOutcomeId, secondOutcomeId));
    }

    @Test
    void detect_compatibleChains_reportsNoChainConflict() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(outcomeId, "first", 40),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes, false, eventId, List.of(chainLinking(eventId, outcomeId), chainLinking(eventId, outcomeId)));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    @Test
    void detect_singleLinkingChain_reportsNoChainConflict() {
        var eventId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 40),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes =
                ParadoxDetector.detect(outcomes, false, eventId, List.of(chainLinking(eventId, UUID.randomUUID())));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    @Test
    void detect_unlinkedAndInactiveChains_ignored() {
        var eventId = UUID.randomUUID();
        var firstOutcomeId = UUID.randomUUID();
        var secondOutcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 40),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var unlinked = chainLinking(UUID.randomUUID(), firstOutcomeId);
        var broken = brokenChainLinking(eventId, secondOutcomeId);

        var paradoxes = ParadoxDetector.detect(outcomes, false, eventId, List.of(unlinked, broken));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    private static WeaverChain chainLinking(UUID eventId, UUID outcomeId) {
        var chainId = UUID.randomUUID();
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()),
                        new ChainLinkAdded(chainId, eventId, outcomeId, 1)));
    }

    private static WeaverChain brokenChainLinking(UUID eventId, UUID outcomeId) {
        var chainId = UUID.randomUUID();
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()),
                        new ChainLinkAdded(chainId, eventId, outcomeId, 1),
                        new ChainBroken(chainId, "UNRAVEL")));
    }

    @Test
    void detect_combinedTypes_reportsBothImpossibleErasureAndSealBreach() {
        var annihilatedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 50, false, true),
                new Outcome(UUID.randomUUID(), "second", 30),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, true);

        assertThat(paradoxes)
                .extracting(DetectedParadox::type)
                .containsExactlyInAnyOrder(ParadoxType.IMPOSSIBLE_ERASURE, ParadoxType.SEAL_BREACH);
    }
}
