package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.ProbabilityRulesPort;

@ConfigurationProperties("game.rules.probability")
@Validated
public record TimelineRulesProperties(int pushShift, int suppressShift, int swingShift, int floor, int ceiling)
        implements ProbabilityRulesPort {

    public TimelineRulesProperties {
        if (floor > ceiling) {
            throw new IllegalArgumentException(
                    "game.rules.probability.floor must be <= game.rules.probability.ceiling");
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
}
