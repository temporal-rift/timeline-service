package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.execution.Execution;

public interface ExecutionRepository {

    Optional<Execution> find();

    /** Reads the execution holding a write lock until the surrounding transaction ends. */
    Optional<Execution> findWithLock();

    /** Returns whether this call stored the execution; {@code false} means one was already configured. */
    boolean insertIfAbsent(Execution execution);

    void update(Execution execution);

    /**
     * Binds {@code gameId} to the open context if it has no game yet.
     *
     * @return whether this call bound it; {@code false} when the context is gone or already bound
     */
    boolean bindGame(UUID caseKey, UUID gameId);

    /** Whether a game belonged to a context that is already closed. */
    boolean isClosedGame(UUID gameId);

    /** Removes the execution and its seats, and remembers its case and game as closed. */
    void close(UUID caseKey);

    /** The case of the context closed last, which stays known while later contexts come and go. */
    Optional<UUID> lastClosedCaseKey();
}
