package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.timeline.domain.port.out.ParadoxContestRulesPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxDetectionRulesPort;

/**
 * Binds the {@code game.rules.paradox} switches; each absent switch means {@code true}, its default rule.
 * {@code detonate-contests-stabilize} is shared with game-service, which scores the cascade it decides.
 */
@ConfigurationProperties("game.rules.paradox")
public record ParadoxRulesProperties(
        @DefaultValue("true") boolean fateContradiction,
        @DefaultValue("true") boolean detonateContestsStabilize)
        implements ParadoxDetectionRulesPort, ParadoxContestRulesPort {}
