package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.GameScope;

/** An ordinary deployment hosts any number of games, so every game's events are its own. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "false", matchIfMissing = true)
class AllGamesScope implements GameScope {

    @Override
    public boolean admits(InboundRecord inbound) {
        return true;
    }
}
