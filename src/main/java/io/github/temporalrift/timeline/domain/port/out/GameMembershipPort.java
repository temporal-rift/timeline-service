package io.github.temporalrift.timeline.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.membership.GameMembership;

/** Driven port for the service-local game participation and faction read model. */
public interface GameMembershipPort {

    /**
     * Records {@code membership}, overwriting any earlier faction for the same game and player so
     * redelivered assignments converge.
     */
    void save(GameMembership membership);

    /** @return empty when the player never received a faction in this game */
    Optional<GameMembership> find(UUID gameId, UUID playerId);
}
