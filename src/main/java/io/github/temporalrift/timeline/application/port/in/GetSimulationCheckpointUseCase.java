package io.github.temporalrift.timeline.application.port.in;

import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;

public interface GetSimulationCheckpointUseCase {

    ExecutionCheckpoint handle();
}
