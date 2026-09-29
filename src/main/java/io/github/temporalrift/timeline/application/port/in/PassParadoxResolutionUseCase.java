package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/**
 * A player's explicit pass during an open paradox-resolution phase: it consumes their single slot without a card,
 * so it counts toward closing the phase once every player has submitted or passed. Idempotent by player within a
 * phase — a redelivered pass, one from a player who already submitted, or one that arrives after the phase already
 * closed, has no further effect.
 */
public interface PassParadoxResolutionUseCase {

    void pass(UUID gameId, int eraNumber, UUID playerId);
}
