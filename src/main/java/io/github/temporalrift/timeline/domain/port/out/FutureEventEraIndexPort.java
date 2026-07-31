package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Write-side lookup so a {@code ResolutionStarted} (which carries only {@code gameId}/{@code eraNumber})
 * can find which {@code FutureEvent} aggregates to resolve — see design.md Decision 1.
 */
public interface FutureEventEraIndexPort {

    void add(UUID eventId, UUID gameId, int eraNumber, int revealIndex);

    /** Ordered by {@code revealIndex} ascending — the {@code EventsDrawn.events} reveal order. */
    List<IndexedEventId> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    record IndexedEventId(UUID eventId, int revealIndex) {}
}
