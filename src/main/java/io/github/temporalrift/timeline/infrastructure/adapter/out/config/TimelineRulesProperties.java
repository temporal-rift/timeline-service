package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.ProbabilityBandRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

@ConfigurationProperties("game.rules.probability")
@Validated
public record TimelineRulesProperties(
        int pushShift,
        int suppressShift,
        int swingShift,
        int floor,
        int ceiling,
        int bandLowMax,
        int bandMediumMax,
        int momentumBonus,
        double rallyMultiplier)
        implements ProbabilityRulesPort, ProbabilityBandRulesPort {

    public TimelineRulesProperties {
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
        if (pushShift <= 0) {
            throw new IllegalArgumentException("game.rules.probability.push-shift must be positive");
        }
        if (suppressShift >= 0) {
            throw new IllegalArgumentException("game.rules.probability.suppress-shift must be negative");
        }
        if (swingShift <= 0) {
            throw new IllegalArgumentException("game.rules.probability.swing-shift must be positive");
        }
        if (bandLowMax < 0 || bandMediumMax <= bandLowMax || bandMediumMax > 100) {
            throw new IllegalArgumentException("game.rules.probability band-low-max/band-medium-max must satisfy "
                    + "0 <= band-low-max < band-medium-max <= 100");
        }
        if (momentumBonus <= 0) {
            throw new IllegalArgumentException("game.rules.probability.momentum-bonus must be positive");
        }
        if (rallyMultiplier <= 1.0) {
            throw new IllegalArgumentException("game.rules.probability.rally-multiplier must be greater than 1.0");
        }
    }

    @Override
    public int pushShift() {
        return pushShift;
    }

    @Override
    public int suppressShift() {
        return suppressShift;
    }

    @Override
    public int swingShift() {
        return swingShift;
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
