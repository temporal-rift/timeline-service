package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ClockOperation;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContextTestData;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;
import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

/** The three simulation-only repositories: the pinned execution, its recorded clock operations and entropy words. */
public interface SimulationRepositoriesContract extends TransactionalContract {

    ExecutionRepository executions();

    ClockOperationRepository clockOperations();

    EntropyDecisionRepository entropyDecisions();

    @Test
    @DisplayName("executions: the first configuration is stored, a second is refused, and none exists beforehand")
    default void executions_firstConfigurationWins() {
        var configured = Execution.configure(ExecutionContextTestData.context("42"));
        assertThat(executions().find()).isEmpty();
        assertThat(executions().findWithLock()).isEmpty();

        assertThat(executions().insertIfAbsent(configured)).isTrue();
        assertThat(executions().insertIfAbsent(Execution.configure(ExecutionContextTestData.context("43"))))
                .isFalse();

        assertThat(executions().find()).contains(configured);
        assertThat(executions().findWithLock()).contains(configured);
    }

    @Test
    @DisplayName("executions: an update moves only the revision and logical time")
    default void executions_updateMovesRevisionAndTime() {
        var configured = Execution.configure(ExecutionContextTestData.context("42"));
        executions().insertIfAbsent(configured);
        var later = configured.logicalTime().plusSeconds(30);

        executions().update(new Execution(configured.context(), 3, later));

        assertThat(executions().find()).contains(new Execution(configured.context(), 3, later));
    }

    @Test
    @DisplayName("executions: a transaction holding the execution lock makes another one wait for its commit")
    default void executions_lockWaitsForTheHolder() throws Exception {
        var configured = Execution.configure(ExecutionContextTestData.context("42"));
        executions().insertIfAbsent(configured);
        var held = holdTransaction(() -> executions().findWithLock());
        var second = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> transactions().execute(status -> executions().findWithLock()));

        Thread.sleep(300);
        assertThat(second).isNotDone();
        assertThat(held.commit()).contains(configured);
        assertThat(second.get(30, java.util.concurrent.TimeUnit.SECONDS)).contains(configured);
    }

    @Test
    @DisplayName("clock operations: a saved operation reads back and saving it again is refused")
    default void clockOperations_savedOperationReadsBack() {
        var operationId = UUID.randomUUID();
        var instant = Instant.parse("2026-03-01T10:00:00Z");
        var operation = new ClockOperation(
                new ClockAdvance(operationId, 0, instant), new ClockAcknowledgement(operationId, 1, instant));
        assertThat(clockOperations().find(operationId)).isEmpty();

        clockOperations().save(operation);

        assertThat(clockOperations().find(operationId)).contains(operation);
        assertThatThrownBy(() -> clockOperations().save(operation)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("entropy decisions: the first word drawn at a position is kept")
    default void entropyDecisions_firstWordIsKept() {
        var coordinate = "era=1;" + UUID.randomUUID();
        assertThat(entropyDecisions().find("CARD_DEAL", coordinate, 0)).isEmpty();

        entropyDecisions().save("CARD_DEAL", coordinate, 0, 7L);
        entropyDecisions().save("CARD_DEAL", coordinate, 0, 9L);
        entropyDecisions().save("CARD_DEAL", coordinate, 1, 11L);

        assertThat(entropyDecisions().find("CARD_DEAL", coordinate, 0)).contains(7L);
        assertThat(entropyDecisions().find("CARD_DEAL", coordinate, 1)).contains(11L);
        assertThat(entropyDecisions().find("OTHER", coordinate, 0)).isEmpty();
    }
}
