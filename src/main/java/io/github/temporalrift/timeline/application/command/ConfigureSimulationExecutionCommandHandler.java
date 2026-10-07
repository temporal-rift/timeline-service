package io.github.temporalrift.timeline.application.command;

import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.application.query.ExecutionCheckpointAssembler;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@Service
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class ConfigureSimulationExecutionCommandHandler implements ConfigureSimulationExecutionUseCase {

    private final ExecutionRepository executions;
    private final ExecutionProbe probe;
    private final LogicalClockControl clock;
    private final ExecutionCheckpointAssembler assembler;
    private final TransactionTemplate transaction;

    ConfigureSimulationExecutionCommandHandler(
            ExecutionRepository executions,
            ExecutionProbe probe,
            LogicalClockControl clock,
            ExecutionCheckpointAssembler assembler,
            TransactionTemplate transaction) {
        this.executions = executions;
        this.probe = probe;
        this.clock = clock;
        this.assembler = assembler;
        this.transaction = transaction;
    }

    @Override
    public ExecutionCheckpoint handle(ExecutionContext context) {
        var execution = Objects.requireNonNull(transaction.execute(status -> register(context)));
        clock.advanceTo(execution.logicalTime());
        return assembler.assemble(execution);
    }

    private Execution register(ExecutionContext context) {
        var existing = executions.findWithLock();
        if (existing.isPresent()) {
            return requireSameContext(existing.get(), context);
        }
        if (probe.observe(context.logicalTime()).gameId() != null) {
            throw new ExecutionContextConflictException("The lane already hosts gameplay");
        }
        var created = Execution.configure(context);
        if (executions.insertIfAbsent(created)) {
            return created;
        }
        return requireSameContext(executions.findWithLock().orElseThrow(), context);
    }

    private static Execution requireSameContext(Execution existing, ExecutionContext context) {
        if (!existing.context().equals(context)) {
            throw new ExecutionContextConflictException("A different execution context is already configured");
        }
        return existing;
    }
}
