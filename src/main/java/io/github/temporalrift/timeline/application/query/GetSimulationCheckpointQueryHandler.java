package io.github.temporalrift.timeline.application.query;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.GetSimulationCheckpointUseCase;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@Service
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class GetSimulationCheckpointQueryHandler implements GetSimulationCheckpointUseCase {

    private final ExecutionRepository executions;
    private final ExecutionCheckpointAssembler assembler;

    GetSimulationCheckpointQueryHandler(ExecutionRepository executions, ExecutionCheckpointAssembler assembler) {
        this.executions = executions;
        this.assembler = assembler;
    }

    @Override
    public ExecutionCheckpoint handle() {
        return executions.find().map(assembler::assemble).orElseThrow(ExecutionNotConfiguredException::new);
    }
}
