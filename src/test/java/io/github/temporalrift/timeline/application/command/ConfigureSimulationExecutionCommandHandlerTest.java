package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.application.query.ExecutionCheckpointAssembler;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionCheckpoint;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.ExecutionState;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;
import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe.Observation;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@ExtendWith(MockitoExtension.class)
class ConfigureSimulationExecutionCommandHandlerTest {

    private static final ExecutionContext CONTEXT = ExecutionContextTestData.context("42");
    private static final Execution CONFIGURED = Execution.configure(CONTEXT);

    @Mock
    ExecutionRepository executions;

    @Mock
    ExecutionProbe probe;

    @Mock
    LogicalClockControl clock;

    @Mock
    ExecutionCheckpointAssembler assembler;

    @Mock
    TransactionTemplate transaction;

    private ConfigureSimulationExecutionCommandHandler handler;
    private final ExecutionCheckpoint checkpoint = new ExecutionCheckpoint(
            CONTEXT.caseKey(),
            CONTEXT.manifestDigest(),
            0,
            CONTEXT.logicalTime(),
            null,
            ExecutionState.READY,
            true,
            0,
            0,
            0,
            null,
            List.of());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient()
                .when(transaction.execute(any()))
                .thenAnswer(
                        invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        lenient().when(assembler.assemble(any())).thenReturn(checkpoint);
        handler = new ConfigureSimulationExecutionCommandHandler(executions, probe, clock, assembler, transaction);
    }

    @Test
    @DisplayName("an empty lane accepts the context, moves the clock to its time and reports the checkpoint")
    void handle_emptyLane_storesContextAtRevisionZero() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(probe.observe(any(Instant.class))).willReturn(new Observation(null, false, 0, null));
        given(executions.insertIfAbsent(any())).willReturn(true);

        var result = handler.handle(CONTEXT);

        assertThat(result).isSameAs(checkpoint);
        then(executions).should().insertIfAbsent(CONFIGURED);
        then(clock).should().advanceTo(CONTEXT.logicalTime());
    }

    @Test
    @DisplayName("an identical repeat is a no-op")
    void handle_identicalContext_isNoOp() {
        given(executions.findWithLock()).willReturn(Optional.of(CONFIGURED));

        handler.handle(CONTEXT);

        then(executions).should(never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("a different context is a conflict")
    void handle_differentContext_isConflict() {
        var other = ExecutionContextTestData.context("43");
        given(executions.findWithLock()).willReturn(Optional.of(CONFIGURED));

        assertThatThrownBy(() -> handler.handle(other)).isInstanceOf(ExecutionContextConflictException.class);
    }

    @Test
    @DisplayName("a lane that already hosts a game rejects a first configuration")
    void handle_laneWithGame_isConflict() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(probe.observe(any(Instant.class))).willReturn(new Observation(UUID.randomUUID(), false, 0, null));

        assertThatThrownBy(() -> handler.handle(CONTEXT)).isInstanceOf(ExecutionContextConflictException.class);

        then(executions).should(never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("losing the race to a concurrent identical configuration still succeeds")
    void handle_concurrentIdenticalConfiguration_succeeds() {
        given(executions.findWithLock()).willReturn(Optional.empty()).willReturn(Optional.of(CONFIGURED));
        given(probe.observe(any(Instant.class))).willReturn(new Observation(null, false, 0, null));
        given(executions.insertIfAbsent(any())).willReturn(false);

        assertThat(handler.handle(CONTEXT)).isSameAs(checkpoint);
    }
}
