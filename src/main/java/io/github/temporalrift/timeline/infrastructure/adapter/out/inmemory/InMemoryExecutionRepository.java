package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

/** The single execution row, at the fixed id the relational table uses. */
@Repository
@Profile("in-memory")
class InMemoryExecutionRepository implements ExecutionRepository {

    private static final int ID = 1;

    private final Table<Integer, Execution> executions;
    private final Table<Integer, ClosedExecution> closed;
    private final Table<UUID, ClosedGame> closedGames;

    InMemoryExecutionRepository(InMemoryDatabase database) {
        this.executions = database.table(TableNames.SIMULATION_EXECUTION);
        this.closed = database.table(TableNames.SIMULATION_CLOSED_EXECUTION);
        this.closedGames = database.table(TableNames.SIMULATION_CLOSED_GAME);
    }

    @Override
    public Optional<Execution> find() {
        return executions.find(ID);
    }

    @Override
    public Optional<Execution> findWithLock() {
        return executions.findForUpdate(ID);
    }

    @Override
    public boolean insertIfAbsent(Execution execution) {
        return executions.insertIfAbsent(ID, execution);
    }

    /** Updates only the logical time and revision, leaving the pinned context as configured. */
    @Override
    @Transactional
    public void update(Execution execution) {
        executions.lock(ID);
        executions
                .find(ID)
                .ifPresent(stored -> executions.put(
                        ID,
                        new Execution(
                                stored.context(), execution.revision(), execution.logicalTime(), stored.gameId())));
    }

    /** The in-memory row lock is exclusive only, which still makes a close wait for the handler. */
    @Override
    @Transactional
    public Optional<Bounds> findBoundsForHandling() {
        return executions.findForUpdate(ID).map(InMemoryExecutionRepository::boundsOf);
    }

    @Override
    @Transactional
    public Optional<Bounds> findBoundsForBinding() {
        return executions.findForUpdate(ID).map(InMemoryExecutionRepository::boundsOf);
    }

    @Override
    public Optional<Bounds> findBounds() {
        return executions.find(ID).map(InMemoryExecutionRepository::boundsOf);
    }

    @Override
    @Transactional
    public boolean bindGame(UUID caseKey, UUID gameId) {
        executions.lock(ID);
        return executions
                .find(ID)
                .filter(stored -> stored.context().caseKey().equals(caseKey) && stored.gameId() == null)
                .map(stored -> {
                    executions.put(
                            ID, new Execution(stored.context(), stored.revision(), stored.logicalTime(), gameId));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public boolean isClosedGame(UUID gameId) {
        return closedGames.find(gameId).isPresent();
    }

    @Override
    @Transactional
    public void forgetClosedGames(UUID caseKey) {
        closedGames.entries(closedGame -> closedGame.caseKey().equals(caseKey)).stream()
                .map(Map.Entry::getKey)
                .forEach(closedGames::remove);
    }

    @Override
    @Transactional
    public void close(UUID caseKey) {
        executions.findForUpdate(ID).ifPresent(stored -> {
            var gameId = stored.gameId();
            if (gameId != null) {
                closedGames.put(gameId, new ClosedGame(caseKey));
            }
            executions.remove(ID);
        });
        closed.put(ID, new ClosedExecution(caseKey));
    }

    @Override
    public Optional<UUID> lastClosedCaseKey() {
        return closed.find(ID).map(ClosedExecution::caseKey);
    }

    private static Bounds boundsOf(Execution execution) {
        return new Bounds(execution.context().caseKey(), execution.gameId());
    }

    private record ClosedExecution(UUID caseKey) {}

    private record ClosedGame(UUID caseKey) {}
}
