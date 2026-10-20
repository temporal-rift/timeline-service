package io.github.temporalrift.timeline.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.timeline.domain.event.ExecutionClosed;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextConflictException;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;
import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

@ExtendWith(MockitoExtension.class)
class CloseSimulationExecutionCommandHandlerTest {

    private static final UUID CASE_KEY = ExecutionContextTestData.CASE_KEY;
    private static final UUID OTHER_CASE = UUID.fromString("c0ffee00-0000-4000-8000-000000000002");
    private static final Execution OPEN = Execution.configure(ExecutionContextTestData.context("42"));

    @Mock
    ExecutionRepository executions;

    @Mock
    ClockOperationRepository operations;

    @Mock
    EntropyDecisionRepository decisions;

    @Mock
    GameplayDiscard gameplay;

    @Mock
    LogicalClockControl clock;

    @Mock
    ApplicationEventPublisher events;

    @Mock
    TransactionTemplate transaction;

    private CloseSimulationExecutionCommandHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient()
                .when(transaction.execute(any()))
                .thenAnswer(
                        invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        handler = new CloseSimulationExecutionCommandHandler(
                executions, operations, decisions, gameplay, clock, events, transaction);
    }

    @Test
    @DisplayName("closing the open context discards its work and frees the service")
    void handle_openContext_discardsAndCloses() {
        given(executions.findWithLock()).willReturn(Optional.of(OPEN));

        handler.handle(CASE_KEY);

        then(gameplay).should().discardAll();
        then(operations).should().clear();
        then(decisions).should().clear();
        then(executions).should().close(CASE_KEY);
        then(clock).should().reset();
        then(events).should().publishEvent(new ExecutionClosed(CASE_KEY));
    }

    @Test
    @DisplayName("repeating the most recently closed case is a no-op")
    void handle_lastClosedCase_isNoOp() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(executions.lastClosedCaseKey()).willReturn(Optional.of(CASE_KEY));

        handler.handle(CASE_KEY);

        then(gameplay).should(never()).discardAll();
        then(executions).should(never()).close(any());
        then(clock).should(never()).reset();
    }

    @Test
    @DisplayName("repeating the last closed case while another is open is a no-op")
    void handle_lastClosedCaseWhileAnotherOpen_isNoOp() {
        given(executions.findWithLock()).willReturn(Optional.of(OPEN));
        given(executions.lastClosedCaseKey()).willReturn(Optional.of(OTHER_CASE));

        handler.handle(OTHER_CASE);

        then(gameplay).should(never()).discardAll();
        then(executions).should(never()).close(any());
    }

    @Test
    @DisplayName("an unrelated case is a conflict")
    void handle_unrelatedCase_isConflict() {
        given(executions.findWithLock()).willReturn(Optional.of(OPEN));
        given(executions.lastClosedCaseKey()).willReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(OTHER_CASE)).isInstanceOf(ExecutionContextConflictException.class);
    }

    @Test
    @DisplayName("closing with nothing ever configured is not configured")
    void handle_nothingEverConfigured_isNotConfigured() {
        given(executions.findWithLock()).willReturn(Optional.empty());
        given(executions.lastClosedCaseKey()).willReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(CASE_KEY)).isInstanceOf(ExecutionNotConfiguredException.class);
    }
}
