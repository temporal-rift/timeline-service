package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

public interface WeaverChainSagaRepositoryContract {

    WeaverChainSagaRepository chainSagas();

    @Test
    @DisplayName("chain sagas: saving again overwrites the saga of the same chain")
    default void chainSagas_saveOverwrites() {
        var chainId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        assertThat(chainSagas().findByChainId(chainId)).isEmpty();

        chainSagas().save(saga(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null));
        var completed = saga(chainId, gameId, playerId, WeaverChainSagaStatus.COMPLETED, true, 2);
        chainSagas().save(completed);

        assertThat(chainSagas().findByChainId(chainId)).contains(completed);
    }

    @Test
    @DisplayName("chain sagas: lookups by game and player filter by status and scope")
    default void chainSagas_lookupsFilterByStatusAndScope() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var open = saga(UUID.randomUUID(), gameId, playerId, WeaverChainSagaStatus.OPEN, false, null);
        var completed = saga(UUID.randomUUID(), gameId, playerId, WeaverChainSagaStatus.COMPLETED, false, null);
        var broken = saga(UUID.randomUUID(), gameId, playerId, WeaverChainSagaStatus.BROKEN, false, null);
        var otherPlayersOpen =
                saga(UUID.randomUUID(), gameId, UUID.randomUUID(), WeaverChainSagaStatus.OPEN, false, null);
        var otherGamesOpen =
                saga(UUID.randomUUID(), UUID.randomUUID(), playerId, WeaverChainSagaStatus.OPEN, false, null);
        for (var state : new WeaverChainSagaState[] {open, completed, broken, otherPlayersOpen, otherGamesOpen}) {
            chainSagas().save(state);
        }

        assertThat(chainSagas().findOpenByGameAndPlayer(gameId, playerId)).contains(open);
        assertThat(chainSagas().findCompletedByGameAndPlayer(gameId, playerId)).contains(completed);
        assertThat(chainSagas().findAllByGameAndPlayer(gameId, playerId))
                .containsExactlyInAnyOrder(open, completed, broken);
        assertThat(chainSagas().findOpenByGame(gameId)).containsExactlyInAnyOrder(open, otherPlayersOpen);
        assertThat(chainSagas().findOpenByGameAndPlayer(UUID.randomUUID(), playerId))
                .isEmpty();
    }

    private static WeaverChainSagaState saga(
            UUID chainId,
            UUID gameId,
            UUID playerId,
            WeaverChainSagaStatus status,
            boolean tapestryProtected,
            Integer tapestryUsedEra) {
        return new WeaverChainSagaState(chainId, gameId, playerId, status, tapestryProtected, tapestryUsedEra, null);
    }
}
