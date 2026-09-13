package io.github.temporalrift.timeline.application.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.application.port.in.GetChainUseCase;
import io.github.temporalrift.timeline.domain.membership.NotGameParticipantException;
import io.github.temporalrift.timeline.domain.membership.NotWeaverException;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainRepository;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;
import io.github.temporalrift.timeline.domain.weaverchain.ChainNotFoundException;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChain;
import io.github.temporalrift.timeline.domain.weaverchain.WeaverChainNotFoundException;

/**
 * Resolves the gated chain read in visibility order: participation first so outsiders learn
 * nothing, then faction, then chain existence.
 */
@Service
class GetChainQueryHandler implements GetChainUseCase {

    private final GameMembershipPort memberships;
    private final WeaverChainSagaRepository sagas;
    private final WeaverChainRepository chains;

    GetChainQueryHandler(
            GameMembershipPort memberships, WeaverChainSagaRepository sagas, WeaverChainRepository chains) {
        this.memberships = memberships;
        this.sagas = sagas;
        this.chains = chains;
    }

    @Override
    @Transactional(readOnly = true)
    public Result get(UUID gameId, UUID playerId) {
        var membership = memberships.find(gameId, playerId).orElseThrow(() -> new NotGameParticipantException(gameId));
        if (!membership.faction().isWeaver()) {
            throw new NotWeaverException();
        }
        var chain = selectChain(gameId, playerId);
        return new Result(chain.chainId(), chain.status(), chain.length(), chain.links());
    }

    /**
     * Terminal saga rows accumulate when a Weaver rebuilds after a break, so several chains can
     * exist for one player: the open one wins, then the longest, then the smallest id. Sagas whose
     * stream holds no events are skipped.
     */
    private WeaverChain selectChain(UUID gameId, UUID playerId) {
        var loaded = new ArrayList<LoadedChain>();
        for (var saga : sagas.findAllByGameAndPlayer(gameId, playerId)) {
            try {
                loaded.add(new LoadedChain(saga, chains.findById(saga.chainId())));
            } catch (WeaverChainNotFoundException _) {
                // Saga row without a stream — ignore and consider the remaining chains.
            }
        }
        if (loaded.isEmpty()) {
            throw new ChainNotFoundException(gameId, playerId);
        }
        loaded.sort(Comparator.comparing(
                        (LoadedChain candidate) -> candidate.saga().status() == WeaverChainSagaStatus.OPEN ? 0 : 1)
                .thenComparing((LoadedChain candidate) -> candidate.chain().length(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.chain().chainId()));
        return loaded.getFirst().chain();
    }

    private record LoadedChain(WeaverChainSagaState saga, WeaverChain chain) {}
}
