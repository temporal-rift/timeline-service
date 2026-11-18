package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.timeline.domain.port.out.ParadoxDetectionRulesPort;

/** Binds {@code game.rules.paradox.fate-contradiction}; absent means {@code true}, the default rule. */
@ConfigurationProperties("game.rules.paradox")
public record ParadoxDetectionRulesProperties(
        @DefaultValue("true") boolean fateContradiction) implements ParadoxDetectionRulesPort {}
