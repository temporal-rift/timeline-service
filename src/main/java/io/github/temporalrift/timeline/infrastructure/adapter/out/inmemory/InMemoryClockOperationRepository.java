package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.ClockOperation;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;

@Component
@Profile("in-memory")
class InMemoryClockOperationRepository implements ClockOperationRepository {

    private final Table<UUID, ClockOperation> operations;

    InMemoryClockOperationRepository(InMemoryDatabase database) {
        this.operations = database.table(TableNames.SIMULATION_CLOCK_OPERATION);
    }

    @Override
    public Optional<ClockOperation> find(UUID operationId) {
        return operations.find(operationId);
    }

    /** A plain insert like the SQL one: saving an operation id twice is a duplicate key. */
    @Override
    public void save(ClockOperation operation) {
        operations.insert(operation.request().operationId(), operation);
    }
}
