package io.github.temporalrift.timeline.domain.futureevent;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects paradox conditions on a {@link FutureEvent}'s final, post-effects outcome state (GDD §6.1). Plain
 * domain logic — no framework dependency — so it can run ahead of the highest-probability winner selection in
 * the resolution use case.
 */
public final class ParadoxDetector {

    private ParadoxDetector() {}

    /**
     * Reports one {@link DetectedParadox} of type {@code IMPOSSIBLE_ERASURE} for every annihilated outcome whose
     * probability is greater than or equal to every non-annihilated outcome's probability (GDD §6.1 Type 2). An
     * event with no non-annihilated outcomes at all trivially satisfies this for each annihilated outcome.
     */
    public static List<DetectedParadox> detect(List<Outcome> outcomes) {
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
        return List.copyOf(paradoxes);
    }

    private static boolean isAtLeastEveryOtherProbability(Outcome annihilated, List<Outcome> nonAnnihilated) {
        return nonAnnihilated.stream().allMatch(o -> annihilated.probability() >= o.probability());
    }
}
