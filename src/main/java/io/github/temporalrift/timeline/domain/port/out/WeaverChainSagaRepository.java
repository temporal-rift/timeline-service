package io.github.temporalrift.timeline.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;

/** Driven port for the durable Weaver chain saga orchestration state. */
public interface WeaverChainSagaRepository {

    /** Finds the saga for one chain, if any. */
    Optional<WeaverChainSagaState> findByChainId(UUID chainId);

    /** Finds the open saga for one player in one game, if any (at most one active chain per player). */
    Optional<WeaverChainSagaState> findOpenByGameAndPlayer(UUID gameId, UUID playerId);

    /** Finds every open saga in one game (for game-end termination). */
    List<WeaverChainSagaState> findOpenByGame(UUID gameId);

    /** Creates or overwrites saga state. */
    void save(WeaverChainSagaState state);
}
