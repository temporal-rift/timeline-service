package io.github.temporalrift.timeline.domain.port.out;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;

/**
 * Driven port for the provisional probability-shift balance values (GDD §3, §3.2, §4.2) — configured, not
 * hard-coded, mirroring {@code game-service}'s {@code BandRulesPort}/{@code ScoringRulesProperties}
 * convention for the same numbers. Magnitude/multiplier lookups are per-{@link CardGrade}: an empty {@link
 * OptionalInt}/{@link OptionalDouble} means that grade has no configured value and the caller must skip the card
 * rather than apply a default (`graded-magnitude-resolution` capability).
 */
public interface ProbabilityRulesPort {

    /** Signed shift applied to a {@code PUSH} card's target outcome (positive) at the given grade. */
    OptionalInt pushShift(CardGrade grade);

    /** Signed shift applied to a {@code SUPPRESS} card's target outcome (negative) at the given grade. */
    OptionalInt suppressShift(CardGrade grade);

    /** Magnitude moved from a {@code SWING} card's source outcome to its target outcome at the given grade. */
    OptionalInt swingShift(CardGrade grade);

    /** Multiplier an {@code AMPLIFY} card of the given grade applies to its eligible target's own magnitude. */
    OptionalDouble amplifyMultiplier(CardGrade grade);

    /** Inclusive lower bound for any outcome's probability. */
    int probabilityFloor();

    /** Inclusive upper bound for any outcome's probability. */
    int probabilityCeiling();

    /** One-time percentage-point bonus a Momentum declaration applies to its declared outcome (GDD §2.2). */
    int momentumBonus();

    /** Multiplier Rally applies to a Round 1 direct transfer's magnitude toward its declared outcome (GDD §2.2). */
    double rallyMultiplier();
}
