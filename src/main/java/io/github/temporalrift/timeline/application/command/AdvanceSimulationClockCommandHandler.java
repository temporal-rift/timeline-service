package io.github.temporalrift.timeline.application.command;

import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.application.port.in.AdvanceSimulationClockUseCase;
import io.github.temporalrift.timeline.domain.event.LogicalClockAdvanced;
import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@Service
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class AdvanceSimulationClockCommandHandler implements AdvanceSimulationClockUseCase {

    private final ExecutionRepository executions;
    private final ClockOperationRepository operations;
    private final LogicalClockControl clockControl;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    AdvanceSimulationClockCommandHandler(
            ExecutionRepository executions,
            ClockOperationRepository operations,
            LogicalClockControl clockControl,
            ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.executions = executions;
        this.operations = operations;
        this.clockControl = clockControl;
        this.events = events;
        this.transaction = transaction;
    }

    /**
     * Due-time handlers run after the advance commits, each in its own transaction; their publications drain
     * through the checkpoint's outbox count. A repeated operation runs them again, so a crash between the advance
     * and its handlers never leaves an acknowledged deadline unprocessed.
     */
    @Override
    public ClockAcknowledgement handle(ClockAdvance advance) {
        var acknowledgement = Objects.requireNonNull(transaction.execute(status -> applyAdvance(advance)));
        clockControl.advanceTo(acknowledgement.logicalTime());
        events.publishEvent(new LogicalClockAdvanced(acknowledgement.logicalTime()));
        return acknowledgement;
    }

    private ClockAcknowledgement applyAdvance(ClockAdvance advance) {
        var execution = executions.findWithLock().orElseThrow(ExecutionNotConfiguredException::new);
        var recorded = operations.find(advance.operationId()).orElse(null);
        var outcome = execution.advance(advance, recorded);
        if (outcome.applied()) {
            executions.update(outcome.execution());
            operations.save(outcome.operation());
        }
        return outcome.operation().acknowledgement();
    }
}
