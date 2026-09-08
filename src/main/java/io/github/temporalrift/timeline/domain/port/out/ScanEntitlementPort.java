package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Driven port for durable SCAN reveal entitlements (scan-probability-reveals capability): one row per
 * {@code (gameId, eraNumber, playerId, eventId)}, created only after same-round NULLIFY cancellation and final
 * post-round event state are known. Consulted at every round close to republish each active entitlement's
 * current exact outcome state, and cleared on {@code EraEnded}/{@code GameEnded}.
 */
public interface ScanEntitlementPort {

    /** Idempotent: repeating an already-recorded {@code (gameId, eraNumber, playerId, eventId)} has no further
     * effect. */
    void upsert(UUID gameId, int eraNumber, UUID playerId, UUID eventId);

    /** Returned in no particular order. */
    List<ScanEntitlement> findByGameAndEra(UUID gameId, int eraNumber);

    /** Idempotent: harmless when no rows exist for {@code (gameId, eraNumber)}. */
    void deleteByGameAndEra(UUID gameId, int eraNumber);

    /** Idempotent: harmless when no rows exist for {@code gameId}. */
    void deleteByGame(UUID gameId);

    record ScanEntitlement(UUID playerId, UUID eventId) {}
}
