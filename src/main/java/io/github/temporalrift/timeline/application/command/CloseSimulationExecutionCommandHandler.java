package io.github.temporalrift.timeline.application.command;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.application.port.in.CloseSimulationExecutionUseCase;
import io.github.temporalrift.timeline.domain.event.ExecutionClosed;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;
import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;
import io.github.temporalrift.timeline.domain.port.out.EventFence;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@Service
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class CloseSimulationExecutionCommandHandler implements CloseSimulationExecutionUseCase {

    private final ExecutionRepository executions;
    private final ClockOperationRepository operations;
    private final EntropyDecisionRepository decisions;
    private final GameplayDiscard gameplay;
    private final EventFence eventFence;
    private final LogicalClockControl clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    CloseSimulationExecutionCommandHandler(
            ExecutionRepository executions,
            ClockOperationRepository operations,
            EntropyDecisionRepository decisions,
            GameplayDiscard gameplay,
            EventFence eventFence,
            LogicalClockControl clock,
            ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.executions = executions;
        this.operations = operations;
        this.decisions = decisions;
        this.gameplay = gameplay;
        this.eventFence = eventFence;
        this.clock = clock;
        this.events = events;
        this.transaction = transaction;
    }

    /**
     * The context's work is discarded in the transaction that closes it, so no handler can act on it afterwards; the
     * in-process views of the context are dropped once that transaction committed.
     */
    @Override
    public void handle(UUID caseKey) {
        var outcome = transaction.execute(status -> close(caseKey));
        if (outcome == Outcome.CLOSED) {
            // Drop the in-process views before a broker call that can fail, so a committed close always frees them.
            clock.reset();
            events.publishEvent(new ExecutionClosed(caseKey));
        }
        if (outcome == Outcome.CLOSED || outcome == Outcome.LAST_CLOSED) {
            // Recorded after the close commits, so a failed close never fences records of a context that stays open.
            // Every record already in the source when this returns belongs to a closed context, even one that never
            // bound a game.
            eventFence.capture();
        }
    }

    private Outcome close(UUID caseKey) {
        var open = executions.findWithLock();
        if (open.isPresent()) {
            return closeOpen(open.get(), caseKey);
        }
        var lastClosed = executions.lastClosedCaseKey().orElseThrow(ExecutionNotConfiguredException::new);
        requireClosedEarlier(lastClosed, caseKey);
        return Outcome.LAST_CLOSED;
    }

    private Outcome closeOpen(Execution open, UUID caseKey) {
        if (open.context().caseKey().equals(caseKey)) {
            gameplay.discardAll();
            operations.clear();
            decisions.clear();
            executions.close(caseKey);
            return Outcome.CLOSED;
        }
        requireClosedEarlier(executions.lastClosedCaseKey().orElse(null), caseKey);
        // Repeating the last closed case while another is open is a no-op; it must not fence the open context.
        return Outcome.LAST_CLOSED_WHILE_OPEN;
    }

    private static void requireClosedEarlier(UUID lastClosed, UUID caseKey) {
        if (!caseKey.equals(lastClosed)) {
            throw new ExecutionContextConflictException("The case is neither the open context nor the last closed one");
        }
    }

    private enum Outcome {
        CLOSED,
        LAST_CLOSED,
        LAST_CLOSED_WHILE_OPEN
    }
}
