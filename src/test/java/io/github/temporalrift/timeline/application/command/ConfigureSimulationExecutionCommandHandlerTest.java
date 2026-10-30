package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

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
import io.github.temporalrift.timeline.domain.execution.ExecutionStateNotCleanException;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionContextException;
import io.github.temporalrift.timeline.domain.execution.InvalidExecutionRulesException;
import io.github.temporalrift.timeline.domain.port.out.EventFence;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRulesValidator;
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@ExtendWith(MockitoExtension.class)
class ConfigureSimulationExecutionCommandHandlerTest {

    private static final ExecutionContext CONTEXT = ExecutionContextTestData.context("42");
    private static final Execution CONFIGURED = Execution.configure(CONTEXT);

    @Mock
    ExecutionRepository executions;

    @Mock
    ExecutionRulesValidator rulesValidator;

    @Mock
    GameplayDiscard gameplay;

    @Mock
    EventFence eventFence;

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
            CONTEXT.rulesDigest(),
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
        lenient().when(gameplay.isClean()).thenReturn(true);
        handler = new ConfigureSimulationExecutionCommandHandler(
                executions,
                gameplay,
                List.of(rulesValidator),
                eventFence,
                clock,
                assembler,
                new SimulationControlLock(),
                transaction);
    }

    @Test
    @DisplayName("an empty lane accepts the context, moves the clock to its time and reports the checkpoint")
    void handle_emptyLane_storesContextAtRevisionZero() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(executions.insertIfAbsent(any())).willReturn(true);

        var result = handler.handle(CONTEXT);

        assertThat(result).isSameAs(checkpoint);
        then(executions).should().insertIfAbsent(CONFIGURED);
        then(clock).should().advanceTo(CONTEXT.logicalTime());
    }

    @Test
    @DisplayName("an empty lane fences the source before it stores the context")
    void handle_emptyLane_capturesTheFenceBeforeStoring() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(executions.insertIfAbsent(any())).willReturn(true);

        handler.handle(CONTEXT);

        var order = inOrder(eventFence, transaction, executions);
        order.verify(eventFence).capture();
        order.verify(transaction).execute(any());
        order.verify(executions).insertIfAbsent(CONFIGURED);
    }

    @Test
    @DisplayName("a capture the broker cannot complete opens nothing")
    void handle_failedCapture_storesNothing() {
        willThrow(new IllegalStateException("Could not record the event fence"))
                .given(eventFence)
                .capture();

        assertThatThrownBy(() -> handler.handle(CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not record the event fence");

        then(transaction).should(never()).execute(any());
        then(executions).should(never()).insertIfAbsent(any());
        then(executions).should(never()).forgetClosedGames(any());
        then(clock).should(never()).advanceTo(any());
    }

    @Test
    @DisplayName("opening a context makes the closed game of the same case admissible again")
    void handle_emptyLane_forgetsTheCasesClosedGames() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(executions.insertIfAbsent(any())).willReturn(true);

        handler.handle(CONTEXT);

        then(executions).should().forgetClosedGames(CONTEXT.caseKey());
    }

    @Test
    @DisplayName("an identical repeat is a no-op")
    void handle_identicalContext_isNoOp() {
        given(executions.find()).willReturn(Optional.of(CONFIGURED));
        given(executions.findWithLock()).willReturn(Optional.of(CONFIGURED));

        handler.handle(CONTEXT);

        then(executions).should(never()).insertIfAbsent(any());
        then(eventFence).should(never()).capture();
    }

    @Test
    @DisplayName("a different context is a conflict")
    void handle_differentContext_isConflict() {
        var other = ExecutionContextTestData.context("43");
        given(executions.find()).willReturn(Optional.of(CONFIGURED));
        given(executions.findWithLock()).willReturn(Optional.of(CONFIGURED));

        assertThatThrownBy(() -> handler.handle(other)).isInstanceOf(ExecutionContextConflictException.class);
        then(eventFence).should(never()).capture();
    }

    @Test
    @DisplayName("a rule the service uses is validated before the context is stored")
    void handle_invalidRules_isRejectedBeforeStoring() {
        willThrow(new InvalidExecutionRulesException("game.rules.probability.suppress-shift.I", "must be negative"))
                .given(rulesValidator)
                .validate(any());

        assertThatThrownBy(() -> handler.handle(CONTEXT))
                .isInstanceOf(InvalidExecutionContextException.class)
                .hasMessageContaining("game.rules.probability.suppress-shift.I");

        then(executions).should(never()).insertIfAbsent(any());
        then(eventFence).should(never()).capture();
    }

    @Test
    @DisplayName("leftover gameplay of an earlier context refuses a first configuration as not clean")
    void handle_leftoverGameplay_isNotClean() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(gameplay.isClean()).willReturn(false);

        assertThatThrownBy(() -> handler.handle(CONTEXT)).isInstanceOf(ExecutionStateNotCleanException.class);

        then(executions).should(never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("losing the race to a concurrent identical configuration still succeeds")
    void handle_concurrentIdenticalConfiguration_succeeds() {
        given(executions.findWithLock()).willReturn(Optional.empty()).willReturn(Optional.of(CONFIGURED));
        given(executions.insertIfAbsent(any())).willReturn(false);

        assertThat(handler.handle(CONTEXT)).isSameAs(checkpoint);
    }
}
