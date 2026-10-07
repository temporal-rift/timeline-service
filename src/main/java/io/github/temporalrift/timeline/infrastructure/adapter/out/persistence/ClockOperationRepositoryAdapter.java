package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.ExecutionRepositoryAdapter.instant;
import static io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.ExecutionRepositoryAdapter.utc;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.ClockAcknowledgement;
import io.github.temporalrift.timeline.domain.execution.ClockAdvance;
import io.github.temporalrift.timeline.domain.execution.ClockOperation;
import io.github.temporalrift.timeline.domain.port.out.ClockOperationRepository;

@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class ClockOperationRepositoryAdapter implements ClockOperationRepository {

    private final JdbcClient jdbc;

    ClockOperationRepositoryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ClockOperation> find(UUID operationId) {
        return jdbc.sql("""
                        SELECT operation_id, expected_revision, target_time, applied_revision, logical_time
                        FROM simulation_clock_operation WHERE operation_id = :operationId
                        """)
                .param("operationId", operationId)
                .query((rs, row) -> new ClockOperation(
                        new ClockAdvance(
                                rs.getObject("operation_id", UUID.class),
                                rs.getLong("expected_revision"),
                                instant(rs, "target_time")),
                        new ClockAcknowledgement(
                                rs.getObject("operation_id", UUID.class),
                                rs.getLong("applied_revision"),
                                instant(rs, "logical_time"))))
                .optional();
    }

    @Override
    public void save(ClockOperation operation) {
        jdbc.sql("""
                        INSERT INTO simulation_clock_operation
                            (operation_id, expected_revision, target_time, applied_revision, logical_time)
                        VALUES (:operationId, :expectedRevision, :targetTime, :appliedRevision, :logicalTime)
                        """)
                .param("operationId", operation.request().operationId())
                .param("expectedRevision", operation.request().expectedRevision())
                .param("targetTime", utc(operation.request().targetTime()))
                .param("appliedRevision", operation.acknowledgement().appliedRevision())
                .param("logicalTime", utc(operation.acknowledgement().logicalTime()))
                .update();
    }
}
