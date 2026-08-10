package io.github.temporalrift.timeline.domain.port.out;

/**
 * Driven port for the provisional probability-shift balance values (GDD §3, §4.2) — configured, not
 * hard-coded, mirroring {@code game-service}'s {@code BandRulesPort}/{@code ScoringRulesProperties}
 * convention for the same numbers.
 */
public interface ProbabilityRulesPort {

    /** Signed shift applied to a {@code PUSH} card's target outcome (positive). */
    int pushShift();

    /** Signed shift applied to a {@code SUPPRESS} card's target outcome (negative). */
    int suppressShift();

    /** Magnitude moved from a {@code SWING} card's source outcome to its target outcome. */
    int swingShift();

    /** Inclusive lower bound for any outcome's probability. */
    int probabilityFloor();

    /** Inclusive upper bound for any outcome's probability. */
    int probabilityCeiling();

    /** One-time percentage-point bonus a Momentum declaration applies to its declared outcome (GDD §2.2). */
    int momentumBonus();

    /** Multiplier Rally applies to a Round 1 direct transfer's magnitude toward its declared outcome (GDD §2.2). */
    double rallyMultiplier();
}
