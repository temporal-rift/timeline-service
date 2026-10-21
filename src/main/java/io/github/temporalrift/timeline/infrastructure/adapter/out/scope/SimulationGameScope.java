package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameScope;

/**
 * Admits only the game the open context hosts. The first admitted record of a context binds its game, because
 * timeline-service learns the game id from {@code game.events} and its membership feed is a separate consumer group.
 * Games of contexts that are already closed stay excluded, so their late records never reach the next game.
 *
 * <p>The check reads the execution row {@code FOR SHARE} in the handler's transaction. A close takes the same row
 * {@code FOR UPDATE}, so it either waits for a handler that already passed this check or makes a later handler find no
 * context; a handler can never write into the tables a close just emptied.
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
        if (gameId == null) {
            return false;
        }
        var bounds = executions.findBoundsForScope().orElse(null);
        if (bounds == null) {
            return false;
        }
        if (bounds.gameId() != null) {
            return bounds.gameId().equals(gameId);
        }
        if (executions.isClosedGame(gameId)) {
            return false;
        }
        if (executions.bindGame(bounds.caseKey(), gameId)) {
            return true;
        }
        // Another consumer group bound a game first; only that game is in scope.
        return executions
                .findBounds()
                .map(ExecutionRepository.Bounds::gameId)
                .filter(gameId::equals)
                .isPresent();
    }
}
