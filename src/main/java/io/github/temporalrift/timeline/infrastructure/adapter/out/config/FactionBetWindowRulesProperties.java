package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.FactionBetWindowRulesPort;

/** Binds the shared {@code game.rules.faction-bet-latest-round}; absent means 2, the default rule. */
@ConfigurationProperties("game.rules")
@Validated
public record FactionBetWindowRulesProperties(
        @DefaultValue("2") @Min(1) @Max(3) int factionBetLatestRound) implements FactionBetWindowRulesPort {}
