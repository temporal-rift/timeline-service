package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

@Repository
class JpaWeaverChainSagaAdapter implements WeaverChainSagaRepository {

    private final WeaverChainSagaJpaRepository jpaRepository;

    JpaWeaverChainSagaAdapter(WeaverChainSagaJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<WeaverChainSagaState> findByChainId(UUID chainId) {
        return jpaRepository.findByChainId(chainId).map(JpaWeaverChainSagaAdapter::toState);
    }

    @Override
    public Optional<WeaverChainSagaState> findOpenByGameAndPlayer(UUID gameId, UUID playerId) {
        return jpaRepository
                .findByGameIdAndPlayerIdAndStatus(gameId, playerId, WeaverChainSagaStatus.OPEN)
                .map(JpaWeaverChainSagaAdapter::toState);
    }

    @Override
    public List<WeaverChainSagaState> findOpenByGame(UUID gameId) {
        return jpaRepository.findByGameIdAndStatus(gameId, WeaverChainSagaStatus.OPEN).stream()
                .map(JpaWeaverChainSagaAdapter::toState)
                .toList();
    }

    @Override
    @Transactional
    public void save(WeaverChainSagaState state) {
        jpaRepository.save(new WeaverChainSagaEntity(
                state.chainId(),
                state.gameId(),
                state.playerId(),
                state.status(),
                state.tapestryProtected(),
                state.tapestryUsedEra()));
    }

    private static WeaverChainSagaState toState(WeaverChainSagaEntity entity) {
        return new WeaverChainSagaState(
                entity.getChainId(),
                entity.getGameId(),
                entity.getPlayerId(),
                entity.getStatus(),
                entity.isTapestryProtected(),
                entity.getTapestryUsedEra());
    }
}
