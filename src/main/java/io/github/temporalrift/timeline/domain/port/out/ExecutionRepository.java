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
     * Reads the open context's case and bound game, holding the execution row for the caller's transaction so a close
     * cannot commit between this check and the handler's writes. Use {@link #findBounds()} for a plain read.
     */
    Optional<Bounds> findBoundsForScope();

    Optional<Bounds> findBounds();

    /**
     * Binds {@code gameId} to the open context if it has no game yet.
     *
     * @return whether this call bound it; {@code false} when the context is gone or already bound
     */
    boolean bindGame(UUID caseKey, UUID gameId);

    /** Whether a game belonged to a context that is already closed. */
    boolean isClosedGame(UUID gameId);

    /** Admits the game of {@code caseKey} again once its context is reopened; other closed games stay excluded. */
    void forgetClosedGames(UUID caseKey);

    /** Removes the execution and its seats, and remembers its case and game as closed. */
    void close(UUID caseKey);

    /** The case of the context closed last, which stays known while later contexts come and go. */
    Optional<UUID> lastClosedCaseKey();

    /** The lightweight identity a game-event gate needs: the open case and its bound game, if any. */
    record Bounds(UUID caseKey, UUID gameId) {}
}
