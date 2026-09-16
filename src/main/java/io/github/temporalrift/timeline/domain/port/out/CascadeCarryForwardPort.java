package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Driven port for durable CASCADE carry-forward rows, keyed by {@code (gameId, eraNumber, eventId, outcomeId)}
 * where {@code eraNumber} is the era the row currently applies to: the era a surviving submission was armed in
 * (intent), or — once confirmed against final erasure state at era resolution — the following era it will be
 * applied at (pending). One mutable row spans both phases so era resolution simply moves it forward instead of
 * copying between two tables.
 */
public interface CascadeCarryForwardPort {

    /** Records a CASCADE intent that survived same-round NULLIFY cancellation. */
    void arm(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId);

    /** Rows currently associated with {@code (gameId, eraNumber)} — either armed intents or pending applications. */
    List<CascadeCarryForward> findByGameAndEra(UUID gameId, int eraNumber);

    /** Moves an armed row forward to the era it will be applied at, once confirmed erased. */
    void confirm(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, int targetEraNumber);

    /** Removes one row — a rejected (never-erased) intent, or a pending row once applied. */
    void delete(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId);

    /** Idempotent: harmless when no rows exist for {@code gameId}. */
    void deleteByGame(UUID gameId);

    record CascadeCarryForward(UUID playerId, UUID eventId, UUID outcomeId) {}
}
