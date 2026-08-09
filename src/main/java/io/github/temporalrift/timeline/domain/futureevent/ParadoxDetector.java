package io.github.temporalrift.timeline.domain.futureevent;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects paradox conditions on a {@link FutureEvent}'s final, post-effects outcome state (GDD §6.1). Plain
 * domain logic — no framework dependency — so it can run ahead of the highest-probability winner selection in
 * the resolution use case. A single call reports every paradox type independently satisfied by the given state,
 * not just the first one found.
 */
public final class ParadoxDetector {

    private ParadoxDetector() {}

    /**
     * {@code sealBreach} is {@link FutureEvent#sealBreach()} — whether an effect application already recorded a
     * {@code SealBreachRecorded} against this event (GDD §6.1 Type 4). Detection itself stays a pure function of
     * these two inputs so it can run independently of the aggregate.
     */
    public static List<DetectedParadox> detect(List<Outcome> outcomes, boolean sealBreach) {
        var paradoxes = new ArrayList<DetectedParadox>();
        paradoxes.addAll(detectDeadHeat(outcomes));
        paradoxes.addAll(detectImpossibleErasure(outcomes));
        paradoxes.addAll(detectChainConflict());
        paradoxes.addAll(detectSealBreach(outcomes, sealBreach));
        return List.copyOf(paradoxes);
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code DEAD_HEAT} when two or more non-annihilated outcomes
     * share the highest probability among an event's non-annihilated outcomes (GDD §6.1 Type 1) —
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
     * probability is greater than or equal to every non-annihilated outcome's probability (GDD §6.1 Type 2). An
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
     * Deferred stub (GDD §6.1 Type 3): no live Weaver chains exist anywhere in the codebase yet, so this always
     * reports no paradox. The Weaver chain slice changes {@link #detect}'s signature to accept real chain state
     * and replaces this method's body with actual conflict detection.
     */
    private static List<DetectedParadox> detectChainConflict() {
        return List.of();
    }

    /**
     * Reports one {@link DetectedParadox} of type {@code SEAL_BREACH} when {@code sealBreach} is set (GDD §6.1
     * Type 4), with {@code affectedOutcomeIds} containing the event's sealed outcome id(s).
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
