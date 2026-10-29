package io.github.temporalrift.timeline.application.command;

import java.util.List;
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
import io.github.temporalrift.timeline.domain.execution.ExecutionStateNotCleanException;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionContextException;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRulesValidator;
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@Service
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class ConfigureSimulationExecutionCommandHandler implements ConfigureSimulationExecutionUseCase {

    private final ExecutionRepository executions;
    private final GameplayDiscard gameplay;
    private final List<ExecutionRulesValidator> rulesValidators;
    private final LogicalClockControl clock;
    private final ExecutionCheckpointAssembler assembler;
    private final SimulationControlLock controlLock;
    private final TransactionTemplate transaction;

    ConfigureSimulationExecutionCommandHandler(
            ExecutionRepository executions,
            GameplayDiscard gameplay,
            List<ExecutionRulesValidator> rulesValidators,
            LogicalClockControl clock,
            ExecutionCheckpointAssembler assembler,
            SimulationControlLock controlLock,
            TransactionTemplate transaction) {
        this.executions = executions;
        this.gameplay = gameplay;
        this.rulesValidators = rulesValidators;
        this.clock = clock;
        this.assembler = assembler;
        this.controlLock = controlLock;
        this.transaction = transaction;
    }

    @Override
    public ExecutionCheckpoint handle(ExecutionContext context) {
        controlLock.lock();
        try {
            requireUsableRules(context);
            var execution = Objects.requireNonNull(transaction.execute(status -> register(context)));
            clock.advanceTo(execution.logicalTime());
            return assembler.assemble(execution);
        } finally {
            controlLock.unlock();
        }
    }

    private void requireUsableRules(ExecutionContext context) {
        try {
            rulesValidators.forEach(
                    validator -> validator.validate(context.rules().bindable()));
        } catch (InvalidExecutionRulesException e) {
            throw new InvalidExecutionContextException(e.getMessage());
        }
    }

    private Execution register(ExecutionContext context) {
        var existing = executions.findWithLock();
        if (existing.isPresent()) {
            return requireSameContext(existing.get(), context);
        }
        if (!gameplay.isClean()) {
            throw new ExecutionStateNotCleanException();
        }
        var created = Execution.configure(context);
        if (executions.insertIfAbsent(created)) {
            // A replay derives the game of the case's earlier play; the close fence, not the game id, separates them.
            executions.forgetClosedGames(context.caseKey());
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
