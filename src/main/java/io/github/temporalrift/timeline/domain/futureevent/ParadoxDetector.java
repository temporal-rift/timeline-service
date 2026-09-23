package io.github.temporalrift.timeline.domain.futureevent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;

/**
 * Detects paradox conditions on a {@link FutureEvent}'s final, post-effects outcome state. Plain
 * domain logic — no framework dependency — so it can run ahead of the highest-probability winner selection in
 * the resolution use case. A single call reports every paradox type independently satisfied by the given state,
 * not just the first one found.
 */
public final class ParadoxDetector {

    private ParadoxDetector() {}

    /**
     * {@code sealBreach} is {@link FutureEvent#sealBreach()} — whether an effect application already recorded a
     * {@code SealBreachRecorded} against this event. Detection itself stays a pure function of
     * these two inputs so it can run independently of the aggregate.
     */
    public static List<DetectedParadox> detect(List<Outcome> outcomes, boolean sealBreach) {
        return detect(outcomes, sealBreach, null, List.of());
    }

    /**
     * Chain-aware detection for one active event: {@code eventId} is the event under evaluation and
     * {@code chains} are the game's currently loaded chains. Only {@code ACTIVE} chains linking
     * {@code eventId} participate; a null event id or empty chain input reports no conflict.
     */
    public static List<DetectedParadox> detect(
            List<Outcome> outcomes, boolean sealBreach, UUID eventId, List<WeaverChain> chains) {
        var paradoxes = new ArrayList<DetectedParadox>();
        paradoxes.addAll(detectDeadHeat(outcomes));
        paradoxes.addAll(detectImpossibleErasure(outcomes));
        paradoxes.addAll(detectChainConflict(eventId, outcomes, chains));
        paradoxes.addAll(detectSealBreach(outcomes, sealBreach));
        return List.copyOf(paradoxes);
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code DEAD_HEAT} when two or more non-annihilated outcomes
     * share the highest probability among an event's non-annihilated outcomes —
     * {@code affectedOutcomeIds} contains every tied outcome's id.
     */
    private static List<DetectedParadox> detectDeadHeat(List<Outcome> outcomes) {
        var nonAnnihilated = outcomes.stream().filter(o -> !o.annihilated()).toList();
        if (nonAnnihilated.isEmpty()) {
            return List.of();
        }
        int highest =
                nonAnnihilated.stream().mapToInt(Outcome::probability).max().orElseThrow();
        var tied = nonAnnihilated.stream()
                .filter(o -> o.probability() == highest)
                .map(Outcome::outcomeId)
                .toList();
        if (tied.size() < 2) {
            return List.of();
        }
        return List.of(new DetectedParadox(
                ParadoxType.DEAD_HEAT,
                tied,
                "Outcomes " + tied + " are tied at the highest non-annihilated probability " + highest));
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code IMPOSSIBLE_ERASURE} for every annihilated outcome whose
     * probability is greater than or equal to every non-annihilated outcome's probability. An
     * event with no non-annihilated outcomes at all trivially satisfies this for each annihilated outcome.
     */
    private static List<DetectedParadox> detectImpossibleErasure(List<Outcome> outcomes) {
        var nonAnnihilated = outcomes.stream().filter(o -> !o.annihilated()).toList();
        var paradoxes = new ArrayList<DetectedParadox>();
        for (var outcome : outcomes) {
            if (outcome.annihilated() && isAtLeastEveryOtherProbability(outcome, nonAnnihilated)) {
                paradoxes.add(new DetectedParadox(
                        ParadoxType.IMPOSSIBLE_ERASURE,
                        List.of(outcome.outcomeId()),
                        "Annihilated outcome " + outcome.outcomeId() + " holds probability " + outcome.probability()
                                + ", which is >= every non-annihilated outcome's probability"));
            }
        }
        return paradoxes;
    }

    private static boolean isAtLeastEveryOtherProbability(Outcome annihilated, List<Outcome> nonAnnihilated) {
        return nonAnnihilated.stream().allMatch(o -> annihilated.probability() >= o.probability());
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code CHAIN_CONFLICT} per active chain whose pending link
     * names {@code eventId} and whose named outcome was annihilated — the timeline erased the outcome the
     * chain's causal claim depends on. Tapestry-protected annihilations never reach here: protection consumes
     * the Annihilate and confirms the link before this detection ever runs (see {@code WeaverChainSaga}).
     */
    private static List<DetectedParadox> detectChainConflict(
            UUID eventId, List<Outcome> outcomes, List<WeaverChain> chains) {
        if (eventId == null || chains == null || chains.isEmpty()) {
            return List.of();
        }
        Set<UUID> annihilatedOutcomeIds = new HashSet<>();
        for (var outcome : outcomes) {
            if (outcome.annihilated()) {
                annihilatedOutcomeIds.add(outcome.outcomeId());
            }
        }
        var paradoxes = new ArrayList<DetectedParadox>();
        for (var chain : chains) {
            if (hasAnnihilatedPendingLink(chain, eventId, annihilatedOutcomeIds)) {
                var pending = chain.pendingLink();
                paradoxes.add(new DetectedParadox(
                        ParadoxType.CHAIN_CONFLICT,
                        List.of(pending.outcomeId()),
                        "Weaver chain's pending link requires outcome " + pending.outcomeId() + " for event " + eventId
                                + ", which was annihilated"));
            }
        }
        return paradoxes;
    }

    private static boolean hasAnnihilatedPendingLink(WeaverChain chain, UUID eventId, Set<UUID> annihilatedOutcomeIds) {
        if (chain == null || chain.status() != ChainStatus.ACTIVE) {
            return false;
        }
        var pending = chain.pendingLink();
        return pending != null
                && eventId.equals(pending.eventId())
                && annihilatedOutcomeIds.contains(pending.outcomeId());
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code SEAL_BREACH} when {@code sealBreach} is set,
     * with {@code affectedOutcomeIds} containing the event's sealed outcome id(s). The breach is the
     * blocked attempt itself — see {@link FutureEvent#sealOutcome}: sealed weights never move, so a set
     * flag always means a shift was declined for naming (or being unable to avoid) a sealed outcome.
     */
    private static List<DetectedParadox> detectSealBreach(List<Outcome> outcomes, boolean sealBreach) {
        if (!sealBreach) {
            return List.of();
        }
        var sealedOutcomeIds = outcomes.stream()
                .filter(Outcome::sealed)
                .map(Outcome::outcomeId)
                .toList();
        return List.of(new DetectedParadox(
                ParadoxType.SEAL_BREACH,
                sealedOutcomeIds,
                "Sealed outcome(s) " + sealedOutcomeIds + " had a probability change attempted against them"));
    }
}
