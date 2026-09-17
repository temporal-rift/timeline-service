package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

@ConfigurationProperties("game.rules.probability")
@Validated
public record TimelineRulesProperties(
        Map<CardGrade, Integer> pushShift,
        Map<CardGrade, Integer> suppressShift,
        Map<CardGrade, Integer> swingShift,
        Map<CardGrade, Double> amplifyMultiplier,
        int floor,
        int ceiling,
        int bandLowMax,
        int bandMediumMax,
        int momentumBonus,
        double rallyMultiplier,
        int threadShift)
        implements ProbabilityRulesPort, ProbabilityBandRulesPort {

    public TimelineRulesProperties {
        requireAllGrades(pushShift, "push-shift");
        requireAllGrades(suppressShift, "suppress-shift");
        requireAllGrades(swingShift, "swing-shift");
        requireAllGrades(amplifyMultiplier, "amplify-multiplier");
        requireFloorCeiling(floor, ceiling);
        requireSign(pushShift, "push-shift", v -> v > 0, "positive");
        requireSign(suppressShift, "suppress-shift", v -> v < 0, "negative");
        requireSign(swingShift, "swing-shift", v -> v > 0, "positive");
        requireAmplifyMultiplier(amplifyMultiplier);
        requireBandThresholds(bandLowMax, bandMediumMax);
        requirePositive(momentumBonus, "momentum-bonus");
        requirePositive(threadShift, "thread-shift");
        requireRallyMultiplier(rallyMultiplier);
        requireNoBoostedMagnitudeOverflow(pushShift, swingShift, amplifyMultiplier, rallyMultiplier);
    }

    private static void requireAllGrades(Map<CardGrade, ?> values, String property) {
        Objects.requireNonNull(values, "game.rules.probability." + property + " must not be null");
        var missing = Arrays.stream(CardGrade.values())
                .filter(grade -> !values.containsKey(grade))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "game.rules.probability." + property + " is missing entries for: " + missing);
        }
    }

    private static void requireFloorCeiling(int floor, int ceiling) {
        if (floor < 0 || ceiling > 100 || floor > ceiling) {
            throw new IllegalArgumentException(
                    "game.rules.probability floor/ceiling must satisfy 0 <= floor <= ceiling <= 100");
        }
        // FutureEvent.clampPairPreservingSum guarantees both redistributed outcomes stay within
        // [floor, ceiling] only when the pair's fixed sum (100 - desiredTarget, for every possible
        // desiredTarget in [floor, ceiling]) itself lands inside [2*floor, 2*ceiling]. That holds for
        // every desiredTarget exactly when these two inequalities hold.
        if (floor + 2 * ceiling < 100 || 2 * floor + ceiling > 100) {
            throw new IllegalArgumentException(
                    "game.rules.probability floor/ceiling must keep a single-outcome shift's remaining pair "
                            + "feasible: floor + 2*ceiling >= 100 and 2*floor + ceiling <= 100");
        }
    }

    private static void requireSign(
            Map<CardGrade, Integer> values, String property, IntPredicate accepted, String sign) {
        if (values.values().stream().mapToInt(Integer::intValue).anyMatch(accepted.negate())) {
            throw new IllegalArgumentException("game.rules.probability." + property + " values must be " + sign);
        }
    }

    private static void requireAmplifyMultiplier(Map<CardGrade, Double> amplifyMultiplier) {
        if (amplifyMultiplier.values().stream().anyMatch(v -> !Double.isFinite(v) || v <= 1.0)) {
            throw new IllegalArgumentException(
                    "game.rules.probability.amplify-multiplier values must be finite numbers greater than 1.0");
        }
    }

    private static void requireBandThresholds(int bandLowMax, int bandMediumMax) {
        if (bandLowMax < 0 || bandMediumMax <= bandLowMax || bandMediumMax > 100) {
            throw new IllegalArgumentException("game.rules.probability band-low-max/band-medium-max must satisfy "
                    + "0 <= band-low-max < band-medium-max <= 100");
        }
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("game.rules.probability." + property + " must be positive");
        }
    }

    private static void requireRallyMultiplier(double rallyMultiplier) {
        if (!Double.isFinite(rallyMultiplier) || rallyMultiplier <= 1.0) {
            throw new IllegalArgumentException(
                    "game.rules.probability.rally-multiplier must be a finite number greater than 1.0");
        }
    }

    /**
     * rallyAdjustedMagnitude rounds magnitude * rallyMultiplier into a long, then narrows to int — an overflowing
     * long silently wraps instead of throwing, so the largest boostable base magnitude (AMPLIFY doubles PUSH/SWING
     * before Rally multiplies it) must stay within int range up front.
     */
    private static void requireNoBoostedMagnitudeOverflow(
            Map<CardGrade, Integer> pushShift,
            Map<CardGrade, Integer> swingShift,
            Map<CardGrade, Double> amplifyMultiplier,
            double rallyMultiplier) {
        double maxBaseMagnitude = Math.max(
                pushShift.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                swingShift.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        double maxAmplifyMultiplier = amplifyMultiplier.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(1.0);
        double maxBoostedMagnitude = maxBaseMagnitude * maxAmplifyMultiplier * rallyMultiplier;
        if (maxBoostedMagnitude > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "game.rules.probability.rally-multiplier is too large: it would let a boosted PUSH/SWING "
                            + "magnitude overflow an int");
        }
    }

    // requireAllGrades guarantees every CardGrade key is present, so these lookups never return null.

    @Override
    public int pushShift(CardGrade grade) {
        return pushShift.get(grade);
    }

    @Override
    public int suppressShift(CardGrade grade) {
        return suppressShift.get(grade);
    }

    @Override
    public int swingShift(CardGrade grade) {
        return swingShift.get(grade);
    }

    @Override
    public double amplifyMultiplier(CardGrade grade) {
        return amplifyMultiplier.get(grade);
    }

    @Override
    public int probabilityFloor() {
        return floor;
    }

    @Override
    public int probabilityCeiling() {
        return ceiling;
    }

    @Override
    public int momentumBonus() {
        return momentumBonus;
    }

    @Override
    public int threadShift() {
        return threadShift;
    }

    @Override
    public double rallyMultiplier() {
        return rallyMultiplier;
    }

    @Override
    public int bandLowMax() {
        return bandLowMax;
    }

    @Override
    public int bandMediumMax() {
        return bandMediumMax;
    }
}
