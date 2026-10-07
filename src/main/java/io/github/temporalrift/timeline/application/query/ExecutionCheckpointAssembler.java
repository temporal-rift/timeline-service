package io.github.temporalrift.timeline.application.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionState;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;

@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
public class ExecutionCheckpointAssembler {

    // Every consumed record completes its effects in its own transaction, so no continuation outlives its offset.
    private static final int CONTINUATIONS_PENDING = 0;

    private final ExecutionProbe probe;
    private final SourceWatermarks watermarks;

    ExecutionCheckpointAssembler(ExecutionProbe probe, SourceWatermarks watermarks) {
        this.probe = probe;
        this.watermarks = watermarks;
    }

    public ExecutionCheckpoint assemble(Execution execution) {
        var observation = probe.observe(execution.logicalTime());
        var outboxPending = probe.pendingPublications();
        return new ExecutionCheckpoint(
                execution.context().caseKey(),
                execution.context().manifestDigest(),
                execution.revision(),
                execution.logicalTime(),
                observation.gameId(),
                stateOf(observation),
                outboxPending == 0 && observation.dueTimers() == 0,
                outboxPending,
                CONTINUATIONS_PENDING,
                observation.dueTimers(),
                observation.nextDeadline(),
                watermarks.current());
    }

    private static ExecutionState stateOf(ExecutionProbe.Observation observation) {
        if (observation.gameId() == null) {
            return ExecutionState.READY;
        }
        return observation.gameEnded() ? ExecutionState.TERMINAL : ExecutionState.ACTIVE;
    }
}
