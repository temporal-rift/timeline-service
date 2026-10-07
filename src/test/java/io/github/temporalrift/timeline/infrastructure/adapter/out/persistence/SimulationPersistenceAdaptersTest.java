package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ClockOperation;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;

/** Statement-level checks; the SQL itself is exercised by the database-backed integration tests. */
class SimulationPersistenceAdaptersTest {

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    @Test
    @DisplayName("a repeated lookup of an unknown entropy word finds nothing")
    void entropyDecisions_unknownWord_isEmpty() {
        var decisions = new EntropyDecisionRepositoryAdapter(jdbc);

        assertThat(decisions.find("CARD_DEAL", "era=1", 0)).isEmpty();
        decisions.save("CARD_DEAL", "era=1", 0, 7L);

        verify(jdbc).sql(org.mockito.ArgumentMatchers.contains("INSERT INTO simulation_entropy_decision"));
    }

    @Test
    @DisplayName("an unknown clock operation is absent and saving one writes its request and acknowledgement")
    void clockOperations_unknownOperation_isAbsent() {
        var operations = new ClockOperationRepositoryAdapter(jdbc);
        var operationId = UUID.randomUUID();
        var instant = Instant.parse("2026-03-01T10:00:00Z");

        assertThat(operations.find(operationId)).isEmpty();
        operations.save(new ClockOperation(
                new ClockAdvance(operationId, 0, instant), new ClockAcknowledgement(operationId, 1, instant)));

        verify(jdbc).sql(org.mockito.ArgumentMatchers.contains("INSERT INTO simulation_clock_operation"));
    }

    @Test
    @DisplayName("an unconfigured lane has no execution, and a rejected insert reports it was not stored")
    void executions_emptyLane_hasNoExecution() {
        var executions = new ExecutionRepositoryAdapter(jdbc);
        var execution = Execution.configure(ExecutionContextTestData.context("42"));

        assertThat(executions.find()).isEmpty();
        assertThat(executions.findWithLock()).isEmpty();
        assertThat(executions.insertIfAbsent(execution)).isFalse();
        executions.update(execution);

        verify(jdbc).sql(org.mockito.ArgumentMatchers.contains("UPDATE simulation_execution"));
    }

    @Test
    @DisplayName("the execution probe counts unrelayed outbox records")
    void outbox_countsIncompletePublications() {
        org.mockito.BDDMockito.given(jdbc.sql(org.mockito.ArgumentMatchers.anyString())
                        .query(Integer.class)
                        .single())
                .willReturn(3);

        assertThat(new ExecutionProbeAdapter(jdbc).pendingPublications()).isEqualTo(3);

        verify(jdbc).sql(org.mockito.ArgumentMatchers.contains("outbox_events"));
    }
}
