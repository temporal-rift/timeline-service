package io.github.temporalrift.timeline.infrastructure.adapter.in.kafka;

import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.Faction;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.SpecialAction;

/**
 * Lenient inbound shape for {@code SpecialActionPlayed}. The published contract marks
 * {@code targetEventId} and {@code targetOutcomeId} required, but player-targeting specials
 * ({@code CORRUPT}, {@code UNRAVEL}, and any future player-targeted special) legitimately carry
 * no event target — game-service validates and publishes them that way. Reading those records
 * into the generated payload type fails Jackson's required-creator check and poison-loops the
 * listener, so this consumer reads the lenient shape instead. All other fields stay required,
 * exactly as the contract declares them.
 */
record SpecialActionPlayedInbound(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID playerId,
        Faction faction,
        SpecialAction specialAction,
        UUID targetEventId,
        UUID targetOutcomeId,
        UUID targetPlayerId) {

    /**
     * Rejects records missing the identity coordinates every downstream use requires. Only the three
     * target coordinates are optional (exactly one targeting mode is populated per play). A malformed
     * record fails loud here — routed to the dead-letter topic — instead of persisting or publishing
     * under a null game/player or a zero era/round.
     */
    void requireEnvelope() {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(specialAction, "specialAction");
        if (eraNumber < 1) {
            throw new IllegalArgumentException("eraNumber must be positive, was " + eraNumber);
        }
        if (roundNumber < 1) {
            throw new IllegalArgumentException("roundNumber must be positive, was " + roundNumber);
        }
    }
}
