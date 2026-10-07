package io.github.temporalrift.timeline.application.port.in;

import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;

public interface ConfigureSimulationExecutionUseCase {

    ExecutionCheckpoint handle(ExecutionContext context);
}
