package io.github.temporalrift.timeline.domain.futureevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.event.ChainBroken;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.ChainLinkThreaded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

class ParadoxDetectorTest {

    @Test
    void detect_annihilatedOutcomeStrictlyHighest_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilated", 50, false, true),
                new Outcome(UUID.randomUUID(), "second", 30),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_annihilatedFreshLeader_reportsNoParadoxDespiteRemainingTie() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 33),
                new Outcome(UUID.randomUUID(), "second", 33),
                new Outcome(UUID.randomUUID(), "leader", 34, false, true));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_erasureLeavingOneEligiblePoint_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilatedA", 50, false, true),
                new Outcome(UUID.randomUUID(), "annihilatedB", 49, false, true),
                new Outcome(UUID.randomUUID(), "eligible", 1));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_erasureLeavingNoEligibleWeight_reportsOneImpossibleErasure() {
        var annihilatedA = UUID.randomUUID();
        var annihilatedB = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedA, "annihilatedA", 50, false, true),
                new Outcome(annihilatedB, "annihilatedB", 50, false, true),
                new Outcome(UUID.randomUUID(), "eligible", 0));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
            assertThat(p.affectedOutcomeIds()).containsExactly(annihilatedA, annihilatedB);
        });
    }

    @Test
    void detect_everyOutcomeAnnihilated_reportsOneImpossibleErasure() {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var outcomes = List.of(
                new Outcome(ids.get(0), "a", 40, false, true),
                new Outcome(ids.get(1), "b", 35, false, true),
                new Outcome(ids.get(2), "c", 25, false, true));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(ParadoxType.IMPOSSIBLE_ERASURE);
            assertThat(p.affectedOutcomeIds()).containsExactlyElementsOf(ids);
        });
    }

    @Test
    void detect_zeroWeightOutcomeWithoutErasure_reportsNoParadox() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 90),
                new Outcome(UUID.randomUUID(), "second", 10),
                new Outcome(UUID.randomUUID(), "third", 0));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_collidedPairTiedAtHighest_reportsDeadHeat() {
        var firstTiedId = UUID.randomUUID();
        var secondTiedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstTiedId, "first", 40),
                new Outcome(secondTiedId, "second", 40),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(secondTiedId, firstTiedId)));

        assertThat(paradoxes).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(ParadoxType.DEAD_HEAT);
            assertThat(p.affectedOutcomeIds()).containsExactlyInAnyOrder(firstTiedId, secondTiedId);
        });
    }

    @Test
    void detect_tieAtHighestWithoutCollide_reportsNoDeadHeat() {
        // A Grade I Suppress on a fresh 34% leader: 24/38/38 is settled by the draw.
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "suppressed", 24),
                new Outcome(UUID.randomUUID(), "first", 38),
                new Outcome(UUID.randomUUID(), "second", 38));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_collidedPairTiedBelowHighest_reportsNoDeadHeat() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstId, "first", 25),
                new Outcome(secondId, "second", 25),
                new Outcome(UUID.randomUUID(), "highest", 50));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(firstId, secondId)));

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_collidedPairNoLongerTied_reportsNoDeadHeat() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstId, "first", 45),
                new Outcome(secondId, "second", 35),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(firstId, secondId)));

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_topTieFormedOutsideTheCollidedPair_reportsNoDeadHeat() {
        // Collide equalized B and C; a later shift tied A with B at the top without re-colliding them.
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        var outcomes = List.of(new Outcome(a, "a", 40), new Outcome(b, "b", 40), new Outcome(c, "c", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(b, c)));

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_threeWayTieContainingCollidedPair_reportsDeadHeatWithAllThree() {
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var thirdId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(firstId, "first", 30),
                new Outcome(secondId, "second", 30),
                new Outcome(thirdId, "third", 30));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(firstId, thirdId)));

        assertThat(paradoxes).singleElement().satisfies(p -> {
            assertThat(p.type()).isEqualTo(ParadoxType.DEAD_HEAT);
            assertThat(p.affectedOutcomeIds()).containsExactlyInAnyOrder(firstId, secondId, thirdId);
        });
    }

    @Test
    void detect_collidedPairWithAnnihilatedMember_reportsNoDeadHeat() {
        var annihilatedId = UUID.randomUUID();
        var eligibleId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(annihilatedId, "annihilated", 40, false, true),
                new Outcome(eligibleId, "eligible", 40),
                new Outcome(UUID.randomUUID(), "third", 20));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(annihilatedId, eligibleId)));

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_collidedPairTiedAtZeroBesideErasure_reportsOnlyImpossibleErasure() {
        var secondId = UUID.randomUUID();
        var thirdId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilated", 100, false, true),
                new Outcome(secondId, "second", 0),
                new Outcome(thirdId, "third", 0));

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of(new CollidedPair(secondId, thirdId)));

        assertThat(paradoxes).extracting(DetectedParadox::type).containsExactly(ParadoxType.IMPOSSIBLE_ERASURE);
    }

    @Test
    void detect_sealBreachSet_reportsSealBreachWithSealedOutcomeIds() {
        var sealedId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(sealedId, "sealed", 40, true, false),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(outcomes, true, List.of());

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

        var paradoxes = ParadoxDetector.detect(outcomes, false, List.of());

        assertThat(paradoxes).isEmpty();
    }

    @Test
    void detect_annihilatedPendingLink_reportsChainConflict() {
        var eventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(pendingOutcomeId, "pending", 40, false, true),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes, false, List.of(), eventId, List.of(pendingChainLinking(eventId, pendingOutcomeId)));

        assertThat(paradoxes)
                .filteredOn(p -> p.type() == ParadoxType.CHAIN_CONFLICT)
                .singleElement()
                .satisfies(p -> assertThat(p.affectedOutcomeIds()).containsExactly(pendingOutcomeId));
    }

    @Test
    void detect_pendingLinkNotAnnihilated_reportsNoChainConflict() {
        var eventId = UUID.randomUUID();
        var pendingOutcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(pendingOutcomeId, "pending", 40),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes, false, List.of(), eventId, List.of(pendingChainLinking(eventId, pendingOutcomeId)));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    @Test
    void detect_pendingLinkOnDifferentEvent_ignored() {
        var eventId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "first", 40, false, true),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes,
                false,
                List.of(),
                eventId,
                List.of(pendingChainLinking(UUID.randomUUID(), UUID.randomUUID())));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    @Test
    void detect_noOpenPendingLink_ignored() {
        var eventId = UUID.randomUUID();
        var confirmedOutcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(confirmedOutcomeId, "confirmed", 40, false, true),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes, false, List.of(), eventId, List.of(confirmedChainLinking(eventId, confirmedOutcomeId)));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    @Test
    void detect_brokenChainWithAnnihilatedPendingCoordinate_ignored() {
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        var outcomes = List.of(
                new Outcome(outcomeId, "first", 40, false, true),
                new Outcome(UUID.randomUUID(), "second", 35),
                new Outcome(UUID.randomUUID(), "third", 25));

        var paradoxes = ParadoxDetector.detect(
                outcomes, false, List.of(), eventId, List.of(brokenChainLinking(eventId, outcomeId)));

        assertThat(paradoxes).noneMatch(p -> p.type() == ParadoxType.CHAIN_CONFLICT);
    }

    private static WeaverChain pendingChainLinking(UUID eventId, UUID outcomeId) {
        var chainId = UUID.randomUUID();
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()),
                        new ChainLinkThreaded(chainId, eventId, outcomeId, 1)));
    }

    private static WeaverChain confirmedChainLinking(UUID eventId, UUID outcomeId) {
        var chainId = UUID.randomUUID();
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()),
                        new ChainLinkThreaded(chainId, eventId, outcomeId, 1),
                        new ChainLinkAdded(chainId, eventId, outcomeId, 1)));
    }

    private static WeaverChain brokenChainLinking(UUID eventId, UUID outcomeId) {
        var chainId = UUID.randomUUID();
        return WeaverChain.replay(
                chainId,
                List.of(
                        new WeaverChainStarted(chainId, UUID.randomUUID(), UUID.randomUUID()),
                        new ChainLinkThreaded(chainId, eventId, outcomeId, 1),
                        new ChainBroken(chainId, "reason")));
    }

    @Test
    void detect_combinedTypes_reportsBothImpossibleErasureAndSealBreach() {
        var outcomes = List.of(
                new Outcome(UUID.randomUUID(), "annihilatedA", 60, false, true),
                new Outcome(UUID.randomUUID(), "annihilatedB", 40, false, true),
                new Outcome(UUID.randomUUID(), "sealed", 0, true, false));

        var paradoxes = ParadoxDetector.detect(outcomes, true, List.of());

        assertThat(paradoxes)
                .extracting(DetectedParadox::type)
                .containsExactlyInAnyOrder(ParadoxType.IMPOSSIBLE_ERASURE, ParadoxType.SEAL_BREACH);
    }
}
