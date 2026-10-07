package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.timeline.application.port.in.AdvanceSimulationClockUseCase;
import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.application.port.in.GetSimulationCheckpointUseCase;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.SimulationExecutionApi;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAcknowledgement;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAdvance;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionCheckpoint;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionContext;

@RestController
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class SimulationControlController implements SimulationExecutionApi {

    private final ConfigureSimulationExecutionUseCase configureExecution;
    private final GetSimulationCheckpointUseCase getCheckpoint;
    private final AdvanceSimulationClockUseCase advanceClock;
    private final SimulationControlMapper mapper;

    SimulationControlController(
            ConfigureSimulationExecutionUseCase configureExecution,
            GetSimulationCheckpointUseCase getCheckpoint,
            AdvanceSimulationClockUseCase advanceClock,
            SimulationControlMapper mapper) {
        this.configureExecution = configureExecution;
        this.getCheckpoint = getCheckpoint;
        this.advanceClock = advanceClock;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<ExecutionCheckpoint> configureSimulationExecution(ExecutionContext executionContext) {
        var checkpoint = configureExecution.handle(mapper.toDomain(executionContext));
        return ResponseEntity.ok(mapper.toModel(checkpoint));
    }

    @Override
    public ResponseEntity<ExecutionCheckpoint> getSimulationCheckpoint() {
        return ResponseEntity.ok(mapper.toModel(getCheckpoint.handle()));
    }

    @Override
    public ResponseEntity<ClockAcknowledgement> advanceSimulationClock(ClockAdvance clockAdvance) {
        var acknowledgement = advanceClock.handle(mapper.toDomain(clockAdvance));
        return ResponseEntity.ok(mapper.toModel(acknowledgement));
    }
}
