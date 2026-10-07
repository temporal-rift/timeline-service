package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.execution.ClockOperation;

public interface ClockOperationRepository {

    Optional<ClockOperation> find(UUID operationId);

    void save(ClockOperation operation);
}
