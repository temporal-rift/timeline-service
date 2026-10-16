package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Optional;

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

    InMemoryExecutionRepository(InMemoryDatabase database) {
        this.executions = database.table(TableNames.SIMULATION_EXECUTION);
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
                        ID, new Execution(stored.context(), execution.revision(), execution.logicalTime())));
    }
}
