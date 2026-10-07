package io.github.temporalrift.timeline.application.query;

import static io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.execution.ExecutionState;
import io.github.temporalrift.timeline.domain.execution.SourceWatermark;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe.Observation;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.SourceWatermarks;

@ExtendWith(MockitoExtension.class)
class ExecutionCheckpointAssemblerTest {

    private static final UUID GAME = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final Execution EXECUTION = Execution.configure(ExecutionContextTestData.context("42"));

    @Mock
    ExecutionProbe probe;

    @Mock
    SourceWatermarks watermarks;

    @Mock
    ExecutionRepository executions;

    private ExecutionCheckpointAssembler assembler() {
        return new ExecutionCheckpointAssembler(probe, watermarks);
    }

    @Test
    @DisplayName("before any game exists the lane is ready and drained")
    void assemble_noGame_isReadyAndDrained() {
        given(probe.observe(START)).willReturn(new Observation(null, false, 0, null));
        given(probe.pendingPublications()).willReturn(0);
        given(watermarks.current()).willReturn(List.of());

        var checkpoint = assembler().assemble(EXECUTION);

        assertThat(checkpoint.state()).isEqualTo(ExecutionState.READY);
        assertThat(checkpoint.drained()).isTrue();
        assertThat(checkpoint.gameId()).isNull();
        assertThat(checkpoint.nextDeadline()).isNull();
        assertThat(checkpoint.revision()).isZero();
    }

    @Test
    @DisplayName("a due paradox timer or an unrelayed publication keeps the checkpoint from draining")
    void assemble_activeGame_reportsPendingWork() {
        var deadline = START.plusSeconds(60);
        given(probe.observe(START)).willReturn(new Observation(GAME, false, 1, deadline));
        given(probe.pendingPublications()).willReturn(4);
        given(watermarks.current()).willReturn(List.of(new SourceWatermark("timeline-service", "game.events", 0, 5)));

        var checkpoint = assembler().assemble(EXECUTION);

        assertThat(checkpoint.state()).isEqualTo(ExecutionState.ACTIVE);
        assertThat(checkpoint.gameId()).isEqualTo(GAME);
        assertThat(checkpoint.dueTimersPending()).isEqualTo(1);
        assertThat(checkpoint.continuationsPending()).isZero();
        assertThat(checkpoint.outboxPending()).isEqualTo(4);
        assertThat(checkpoint.nextDeadline()).isEqualTo(deadline);
        assertThat(checkpoint.drained()).isFalse();
        assertThat(checkpoint.sourceWatermarks()).hasSize(1);
    }

    @Test
    @DisplayName("an active game with only a future deadline is drained")
    void assemble_onlyFutureDeadline_isDrained() {
        given(probe.observe(START)).willReturn(new Observation(GAME, false, 0, START.plusSeconds(60)));
        given(probe.pendingPublications()).willReturn(0);
        given(watermarks.current()).willReturn(List.of());

        assertThat(assembler().assemble(EXECUTION).drained()).isTrue();
    }

    @Test
    @DisplayName("an ended game is terminal")
    void assemble_endedGame_isTerminal() {
        given(probe.observe(START)).willReturn(new Observation(GAME, true, 0, null));
        given(probe.pendingPublications()).willReturn(0);
        given(watermarks.current()).willReturn(List.of());

        assertThat(assembler().assemble(EXECUTION).state()).isEqualTo(ExecutionState.TERMINAL);
    }

    @Test
    @DisplayName("the checkpoint query reads the configured execution and rejects an unconfigured one")
    void queryHandler_readsExecutionOrRejects() {
        lenient().when(probe.observe(any(Instant.class))).thenReturn(new Observation(null, false, 0, null));
        given(probe.pendingPublications()).willReturn(0);
        given(watermarks.current()).willReturn(List.of());
        var query = new GetSimulationCheckpointQueryHandler(executions, assembler());
        given(executions.find()).willReturn(Optional.of(EXECUTION)).willReturn(Optional.empty());

        assertThat(query.handle().caseKey()).isEqualTo(EXECUTION.context().caseKey());
        assertThatThrownBy(query::handle).isInstanceOf(ExecutionNotConfiguredException.class);
    }
}
