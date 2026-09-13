package io.github.temporalrift.timeline.domain.weaverchain;

import java.util.UUID;

/** The requesting Weaver has no chain in this game yet. */
public class ChainNotFoundException extends RuntimeException {

    public ChainNotFoundException(UUID gameId, UUID playerId) {
        super("No chain for player " + playerId + " in game " + gameId);
    }
}
