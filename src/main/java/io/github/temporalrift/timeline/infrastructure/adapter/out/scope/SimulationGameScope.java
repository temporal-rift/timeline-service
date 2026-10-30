package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EventFence;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameScope;

/**
 * Admits only the game the open context hosts. The first admitted record of a context binds its game, because
 * timeline-service learns the game id from {@code game.events} and its membership feed is a separate consumer group.
 * Records already in the source when the context opened stay fenced, and games of contexts that are already closed
 * stay excluded, so their late records never reach the next game.
 *
 * <p>Every decision is taken holding the execution row until the handler's transaction ends. An open captures its
 * fence before its context commits, so a record checked under the lock meets the fence of the context it would act
 * on. A close takes the same row exclusively, so it either waits for a handler that already passed this check or makes
 * a later handler find no context; a handler can never write into the tables a close just emptied.
 */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class SimulationGameScope implements GameScope {

    private final ExecutionRepository executions;
    private final EventFence eventFence;

    SimulationGameScope(ExecutionRepository executions, EventFence eventFence) {
        this.executions = executions;
        this.eventFence = eventFence;
    }

    @Override
    public boolean admits(InboundRecord inbound) {
        var gameId = inbound.gameId();
        if (gameId == null) {
            return false;
        }
        var seen = executions.findBounds().orElse(null);
        if (seen == null) {
            return false;
        }
        // Only the lock to take is chosen without it: a context with no game yet is held exclusively, so its first
        // binders queue on one lock instead of each holding a shared lock the other one needs to upgrade.
        var binding = seen.gameId() == null;
        var bounds = (binding ? executions.findBoundsForBinding() : executions.findBoundsForHandling()).orElse(null);
        if (bounds == null || isFenced(inbound)) {
            return false;
        }
        if (bounds.gameId() != null) {
            return gameId.equals(bounds.gameId());
        }
        // A context opened since the unlocked read fenced every record already in the log, this one included.
        if (!binding || executions.isClosedGame(gameId)) {
            return false;
        }
        return executions.bindGame(bounds.caseKey(), gameId);
    }

    private boolean isFenced(InboundRecord inbound) {
        return inbound.hasPosition() && eventFence.isFenced(inbound.topic(), inbound.partition(), inbound.offset());
    }
}
