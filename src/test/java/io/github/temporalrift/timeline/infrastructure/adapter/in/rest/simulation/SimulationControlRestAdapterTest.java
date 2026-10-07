package io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import io.github.temporalrift.timeline.application.port.in.AdvanceSimulationClockUseCase;
import io.github.temporalrift.timeline.application.port.in.ConfigureSimulationExecutionUseCase;
import io.github.temporalrift.timeline.application.port.in.GetSimulationCheckpointUseCase;
import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockRegressionException;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.execution.ExecutionState;
import io.github.temporalrift.timeline.domain.execution.IdempotencyConflictException;
import io.github.temporalrift.timeline.domain.execution.InvalidClockAdvanceException;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionContextException;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.execution.StaleExecutionRevisionException;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ClockAdvance;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.ExecutionContext;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.Faction;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.simulation.v1.model.SimulationSeat;

@ExtendWith(MockitoExtension.class)
class SimulationControlRestAdapterTest {

    private static final UUID CASE_KEY = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    private static final UUID OPERATION = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001");
    private static final String DIGEST = "a".repeat(64);
    private static final Instant START = Instant.parse("2026-03-01T10:00:00Z");

    @Mock
    ConfigureSimulationExecutionUseCase configureExecution;

    @Mock
    GetSimulationCheckpointUseCase getCheckpoint;

    @Mock
    AdvanceSimulationClockUseCase advanceClock;

    private final SimulationControlExceptionHandler handler = new SimulationControlExceptionHandler();

    private final SimulationControlMapper mapper = new SimulationControlMapperImpl();

    private static ExecutionCheckpoint checkpoint(Instant nextDeadline) {
        return new ExecutionCheckpoint(
                CASE_KEY,
                DIGEST,
                3,
                START,
                OPERATION,
                ExecutionState.ACTIVE,
                false,
                1,
                2,
                3,
                nextDeadline,
                List.of(new SourceWatermark("timeline-service", "game.events", 1, 9)));
    }

    private static ExecutionContext context() {
        return new ExecutionContext(
                ExecutionContext.SchemaVersionEnum.NUMBER_1,
                CASE_KEY,
                "42",
                ExecutionContext.EntropyVersionEnum.SHA256_V1,
                DIGEST,
                OffsetDateTime.ofInstant(START, ZoneOffset.UTC),
                List.of(
                        new SimulationSeat(0, UUID.randomUUID(), Faction.ERASERS),
                        new SimulationSeat(1, UUID.randomUUID(), Faction.PROPHETS),
                        new SimulationSeat(2, UUID.randomUUID(), Faction.WEAVERS)));
    }

    @Test
    @DisplayName("the mapper turns the generated context into a validated domain context")
    void toDomain_context_mapsEveryField() {
        var domain = mapper.toDomain(context());

        assertThat(domain.caseKey()).isEqualTo(CASE_KEY);
        assertThat(domain.seed().decimal()).isEqualTo("42");
        assertThat(domain.manifestDigest()).isEqualTo(DIGEST);
        assertThat(domain.logicalTime()).isEqualTo(START);
        assertThat(domain.seats()).hasSize(3);
        assertThat(domain.seats().get(1).faction().name()).isEqualTo("PROPHETS");
    }

    @Test
    @DisplayName("the mapper turns a clock advance into its domain form and an acknowledgement into its model")
    void clockMapping_roundTrips() {
        var body = new ClockAdvance(OPERATION, 4L, OffsetDateTime.ofInstant(START, ZoneOffset.UTC));

        var domain = mapper.toDomain(body);
        var model = mapper.toModel(new ClockAcknowledgement(OPERATION, 5, START));

        assertThat(domain.operationId()).isEqualTo(OPERATION);
        assertThat(domain.expectedRevision()).isEqualTo(4);
        assertThat(domain.targetTime()).isEqualTo(START);
        assertThat(model.getAppliedRevision()).isEqualTo(5);
        assertThat(model.getLogicalTime().toInstant()).isEqualTo(START);
    }

    @Test
    @DisplayName("the mapper exposes a checkpoint with and without a next deadline")
    void toModel_checkpoint_mapsNullableDeadline() {
        var withDeadline = mapper.toModel(checkpoint(START.plusSeconds(30)));
        var withoutDeadline = mapper.toModel(checkpoint(null));

        assertThat(withDeadline.getNextDeadline().toInstant()).isEqualTo(START.plusSeconds(30));
        assertThat(withDeadline.getState().getValue()).isEqualTo("ACTIVE");
        assertThat(withDeadline.getSourceWatermarks().getFirst().getNextOffset())
                .isEqualTo(9);
        assertThat(withoutDeadline.getNextDeadline()).isNull();
    }

    @Test
    @DisplayName("the controller delegates each operation to its use case")
    void controller_delegatesToUseCases() {
        var controller = new SimulationControlController(configureExecution, getCheckpoint, advanceClock, mapper);
        given(configureExecution.handle(any())).willReturn(checkpoint(null));
        given(getCheckpoint.handle()).willReturn(checkpoint(null));
        given(advanceClock.handle(any())).willReturn(new ClockAcknowledgement(OPERATION, 1, START));

        assertThat(controller.configureSimulationExecution(context()).getBody().getRevision())
                .isEqualTo(3);
        assertThat(controller.getSimulationCheckpoint().getBody().getDrained()).isFalse();
        assertThat(controller
                        .advanceSimulationClock(
                                new ClockAdvance(OPERATION, 0L, OffsetDateTime.ofInstant(START, ZoneOffset.UTC)))
                        .getBody()
                        .getAppliedRevision())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("each domain failure maps to its published status and code")
    void exceptionHandler_mapsDomainFailures() {
        assertProblem(
                handler.handleInvalidContext(new InvalidExecutionContextException("bad")),
                HttpStatus.BAD_REQUEST,
                "INVALID_EXECUTION_CONTEXT");
        assertProblem(
                handler.handleInvalidAdvance(new InvalidClockAdvanceException("bad")),
                HttpStatus.BAD_REQUEST,
                "INVALID_CLOCK_ADVANCE");
        assertProblem(
                handler.handleContextConflict(new ExecutionContextConflictException("x")),
                HttpStatus.CONFLICT,
                "EXECUTION_CONTEXT_CONFLICT");
        assertProblem(
                handler.handleIdempotencyConflict(new IdempotencyConflictException(OPERATION)),
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT");
        assertProblem(
                handler.handleStaleRevision(new StaleExecutionRevisionException(0, 1)),
                HttpStatus.CONFLICT,
                "STALE_EXECUTION_REVISION");
        assertProblem(
                handler.handleClockRegression(new ClockRegressionException(START, START)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "CLOCK_REGRESSION");
        assertProblem(
                handler.handleNotConfigured(new ExecutionNotConfiguredException()),
                HttpStatus.CONFLICT,
                "EXECUTION_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("a malformed body maps to the code of the route it was sent to")
    void exceptionHandler_malformedBodyUsesTheRouteCode() {
        var clockRequest = new MockHttpServletRequest("PUT", "/internal/simulation/v1/clock");
        var executionRequest = new MockHttpServletRequest("PUT", "/internal/simulation/v1/execution");

        assertProblem(
                handler.handleMalformedBody(new IllegalArgumentException(), clockRequest),
                HttpStatus.BAD_REQUEST,
                "INVALID_CLOCK_ADVANCE");
        assertProblem(
                handler.handleMalformedBody(new IllegalArgumentException(), executionRequest),
                HttpStatus.BAD_REQUEST,
                "INVALID_EXECUTION_CONTEXT");
    }

    private static void assertProblem(org.springframework.http.ProblemDetail problem, HttpStatus status, String code) {
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getProperties()).containsEntry("code", code);
    }
}
