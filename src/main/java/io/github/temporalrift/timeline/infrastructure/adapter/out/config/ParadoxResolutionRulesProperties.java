package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionRulesPort;

@ConfigurationProperties("game.rules")
@Validated
public record ParadoxResolutionRulesProperties(int paradoxResolutionTimerSeconds)
        implements ParadoxResolutionRulesPort {

    public ParadoxResolutionRulesProperties {
        if (paradoxResolutionTimerSeconds <= 0) {
            throw new IllegalArgumentException("game.rules.paradox-resolution-timer-seconds must be positive");
        }
    }
}
