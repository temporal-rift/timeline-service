package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Driven port for {@code CORRUPT} specials buffered until their round closes, since the correlated card
 * isn't knowable until then (design.md Decision 4). Multiple {@code CORRUPT}s can target different players
 * in the same round, so this holds a list per round rather than a single last-value pointer.
 */
public interface PendingCorruptPort {

    void save(UUID gameId, int eraNumber, int roundNumber, UUID targetPlayerId);

    /** Every pending {@code CORRUPT}'s {@code targetPlayerId} for this round, in no particular order. */
    List<UUID> find(UUID gameId, int eraNumber, int roundNumber);
}
