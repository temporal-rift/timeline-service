package io.github.temporalrift.timeline.domain.membership;

import java.util.Objects;
import java.util.UUID;

/** Participation plus faction identity of one player in one game. */
public record GameMembership(UUID gameId, UUID playerId, MemberFaction faction) {

    public GameMembership {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(faction, "faction");
    }
}
