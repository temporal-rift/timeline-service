package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.timeline.domain.port.out.TapestryRulesPort;

/** Binds {@code game.rules.tapestry-absorbs-chain-conflict}; absent means {@code false}, the default rule. */
@ConfigurationProperties("game.rules")
public record TapestryRulesProperties(@DefaultValue("false") boolean tapestryAbsorbsChainConflict)
        implements TapestryRulesPort {}
