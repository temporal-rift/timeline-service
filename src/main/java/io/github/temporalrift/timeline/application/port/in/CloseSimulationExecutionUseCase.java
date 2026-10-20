package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

public interface CloseSimulationExecutionUseCase {

    void handle(UUID caseKey);
}
