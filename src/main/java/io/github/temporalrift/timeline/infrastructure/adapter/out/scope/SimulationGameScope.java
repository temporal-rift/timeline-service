package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameScope;

/**
 * Admits only the game the open context hosts. The first admitted record of a context binds its game, because
 * timeline-service learns the game id from {@code game.events} and its membership feed is a separate consumer group.
 * Games of contexts that are already closed stay excluded, so their late records never reach the next game.
 */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class SimulationGameScope implements GameScope {

    private final ExecutionRepository executions;

    SimulationGameScope(ExecutionRepository executions) {
        this.executions = executions;
    }

    @Override
    public boolean admits(UUID gameId) {
        if (gameId == null || executions.isClosedGame(gameId)) {
            return false;
        }
        var open = executions.find().orElse(null);
        if (open == null) {
            return false;
        }
        var bound = open.gameId();
        if (bound != null) {
            return bound.equals(gameId);
        }
        if (executions.bindGame(open.context().caseKey(), gameId)) {
            return true;
        }
        return executions.find().map(Execution::gameId).filter(gameId::equals).isPresent();
    }
}
