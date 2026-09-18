package io.github.temporalrift.timeline.application.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.application.port.in.GetChainUseCase;
import io.github.temporalrift.timeline.domain.membership.NotGameParticipantException;
import io.github.temporalrift.timeline.domain.membership.NotWeaverException;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
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
    private final EraPlayersPort eraPlayers;

    GetChainQueryHandler(
            GameMembershipPort memberships,
            WeaverChainSagaRepository sagas,
            WeaverChainRepository chains,
            EraPlayersPort eraPlayers) {
        this.memberships = memberships;
        this.sagas = sagas;
        this.chains = chains;
        this.eraPlayers = eraPlayers;
    }

    @Override
    @Transactional(readOnly = true)
    public Result get(UUID gameId, UUID playerId) {
        var membership = memberships.find(gameId, playerId).orElseThrow(() -> new NotGameParticipantException(gameId));
        if (!membership.faction().isWeaver()) {
            throw new NotWeaverException();
        }
        var loaded = selectChain(gameId, playerId);
        var chain = loaded.chain();
        return new Result(
                chain.chainId(),
                chain.status(),
                chain.length(),
                chain.links(),
                chain.pendingLink(),
                isProtectionCurrentlyArmed(loaded));
    }

    /**
     * Protection is scoped to the era it was armed in, the same check the saga applies when invalidating a
     * linked outcome — an unconsumed TAPESTRY from an earlier era must not read as active protection now. There
     * is no "current era" concept anywhere else in timeline-service to compare against; the highest era
     * {@code EraStarted} has recorded for this game is the only signal available, and its absence (no era
     * started yet) means there is no current era for the armed flag to apply to.
     *
     * <p>{@code EraStarted} and {@code SpecialActionPlayed} land in different consumer groups, so this read can
     * momentarily under-report a same-instant TAPESTRY as unarmed until the era-players projection catches up —
     * the same eventual-consistency window every other read in this system already accepts.
     */
    private boolean isProtectionCurrentlyArmed(LoadedChain loaded) {
        return eraPlayers
                .findLatestEraNumber(loaded.chain().gameId())
                .map(latestEra -> loaded.saga().tapestryProtected()
                        && latestEra.equals(loaded.saga().tapestryUsedEra()))
                .orElse(false);
    }

    /**
     * Terminal saga rows accumulate when a Weaver rebuilds after a break, so several chains can
     * exist for one player: the open one wins, then the longest, then the smallest id. Sagas whose
     * stream holds no events are skipped.
     */
    private LoadedChain selectChain(UUID gameId, UUID playerId) {
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
        return loaded.getFirst();
    }

    private record LoadedChain(WeaverChainSagaState saga, WeaverChain chain) {}
}
