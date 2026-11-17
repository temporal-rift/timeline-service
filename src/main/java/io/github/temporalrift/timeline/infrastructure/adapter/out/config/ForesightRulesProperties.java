package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.ForesightRulesPort;

/** Binds the shared {@code game.rules.foresight.writings-per-era}; absent means 1, the default rule. */
@ConfigurationProperties("game.rules.foresight")
@Validated
public record ForesightRulesProperties(
        @DefaultValue("1") @Min(1) int writingsPerEra) implements ForesightRulesPort {}
