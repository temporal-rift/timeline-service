package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort.CascadeCarryForward;

public interface CascadeCarryForwardPortContract {

    CascadeCarryForwardPort carryForwards();

    @Test
    @DisplayName("carry forward: arming the same row twice records it once")
    default void carryForwards_armingIsIdempotent() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        carryForwards().arm(gameId, 1, playerId, eventId, outcomeId);
        carryForwards().arm(gameId, 1, playerId, eventId, outcomeId);

        assertThat(carryForwards().findByGameAndEra(gameId, 1))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));
        assertThat(carryForwards().findByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    @DisplayName("carry forward: confirming moves the row to its target era and deleting removes it")
    default void carryForwards_confirmMovesAndDeleteRemoves() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        carryForwards().arm(gameId, 1, playerId, eventId, outcomeId);

        carryForwards().confirm(gameId, 1, eventId, outcomeId, 2);

        assertThat(carryForwards().findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(carryForwards().findByGameAndEra(gameId, 2))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));

        carryForwards().delete(gameId, 2, eventId, outcomeId);
        carryForwards().delete(gameId, 2, eventId, outcomeId);

        assertThat(carryForwards().findByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    @DisplayName("carry forward: deleting by game removes every era of that game only")
    default void carryForwards_deleteByGameIsScoped() {
        var gameId = UUID.randomUUID();
        var otherGame = UUID.randomUUID();
        carryForwards().arm(gameId, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        carryForwards().arm(gameId, 2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        carryForwards().arm(otherGame, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        carryForwards().deleteByGame(gameId);
        carryForwards().deleteByGame(UUID.randomUUID());

        assertThat(carryForwards().findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(carryForwards().findByGameAndEra(gameId, 2)).isEmpty();
        assertThat(carryForwards().findByGameAndEra(otherGame, 1)).hasSize(1);
    }
}
