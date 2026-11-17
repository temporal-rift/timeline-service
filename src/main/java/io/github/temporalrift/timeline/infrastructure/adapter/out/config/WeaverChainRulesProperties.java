package io.github.temporalrift.timeline.infrastructure.adapter.out.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.temporalrift.timeline.domain.port.out.WeaverChainRulesPort;

/**
 * Binds the Weaver chain's completion length from the shared {@code game.rules.victory.weaver-chain-length}, the key
 * game-service reads as the Weaver objective. Only this key of the victory namespace is timeline-service's concern.
 */
@ConfigurationProperties("game.rules.victory")
@Validated
public record WeaverChainRulesProperties(@Min(2) int weaverChainLength) implements WeaverChainRulesPort {

    @Override
    public int completionLength() {
        return weaverChainLength;
    }
}
