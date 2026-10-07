package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;

import io.github.temporalrift.timeline.domain.execution.Execution;

public interface ExecutionRepository {

    Optional<Execution> find();

    /** Reads the execution holding a write lock until the surrounding transaction ends. */
    Optional<Execution> findWithLock();

    /** Returns whether this call stored the execution; {@code false} means one was already configured. */
    boolean insertIfAbsent(Execution execution);

    void update(Execution execution);
}
