package io.github.temporalrift.timeline.domain.port.out;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;

/** Driven port for the provisional probability-shift balance values (GDD §3.2), keyed by {@link CardGrade}. */
public interface ProbabilityRulesPort {

    /** Signed shift applied to a {@code PUSH} card's target outcome (positive) at the given grade. */
    int pushShift(CardGrade grade);

    /** Signed shift applied to a {@code SUPPRESS} card's target outcome (negative) at the given grade. */
    int suppressShift(CardGrade grade);

    /** Magnitude moved from a {@code SWING} card's source outcome to its target outcome at the given grade. */
    int swingShift(CardGrade grade);

    /** Multiplier an {@code AMPLIFY} card of the given grade applies to its eligible target's own magnitude. */
    double amplifyMultiplier(CardGrade grade);

    /** Inclusive lower bound for any outcome's probability. */
    int probabilityFloor();

    /** Inclusive upper bound for any outcome's probability. */
    int probabilityCeiling();

    /** One-time percentage-point bonus a Momentum declaration applies to its declared outcome (GDD §2.2). */
    int momentumBonus();

    /** Multiplier Rally applies to a Round 1 direct transfer's magnitude toward its declared outcome (GDD §2.2). */
    double rallyMultiplier();
}
