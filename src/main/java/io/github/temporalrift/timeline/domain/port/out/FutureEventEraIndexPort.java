package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Write-side lookup so a {@code ResolutionStarted} (which carries only {@code gameId}/{@code eraNumber})
 * can find which {@code FutureEvent} aggregates to resolve — see design.md Decision 1.
 */
public interface FutureEventEraIndexPort {

    void record(UUID eventId, UUID gameId, int eraNumber);

    List<UUID> findEventIdsByGameIdAndEraNumber(UUID gameId, int eraNumber);

    /** Fast-path marker so a future slice can skip loading/replaying already-resolved aggregates. */
    void markResolved(UUID eventId);
}
