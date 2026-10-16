package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

@Component
@Profile("in-memory")
class InMemoryWeaverChainSagaAdapter implements WeaverChainSagaRepository {

    private final Table<UUID, WeaverChainSagaState> sagas;

    InMemoryWeaverChainSagaAdapter(InMemoryDatabase database) {
        this.sagas = database.table(TableNames.WEAVER_CHAIN_SAGA);
    }

    @Override
    public Optional<WeaverChainSagaState> findByChainId(UUID chainId) {
        return sagas.find(chainId);
    }

    @Override
    public Optional<WeaverChainSagaState> findOpenByGameAndPlayer(UUID gameId, UUID playerId) {
        return first(gameId, playerId, WeaverChainSagaStatus.OPEN);
    }

    @Override
    public Optional<WeaverChainSagaState> findCompletedByGameAndPlayer(UUID gameId, UUID playerId) {
        return first(gameId, playerId, WeaverChainSagaStatus.COMPLETED);
    }

    @Override
    public List<WeaverChainSagaState> findAllByGameAndPlayer(UUID gameId, UUID playerId) {
        return sagas.list(
                saga -> saga.gameId().equals(gameId) && saga.playerId().equals(playerId));
    }

    @Override
    public List<WeaverChainSagaState> findOpenByGame(UUID gameId) {
        return sagas.list(saga -> saga.gameId().equals(gameId) && saga.status() == WeaverChainSagaStatus.OPEN);
    }

    @Override
    @Transactional
    public void save(WeaverChainSagaState state) {
        sagas.put(state.chainId(), state);
    }

    private Optional<WeaverChainSagaState> first(UUID gameId, UUID playerId, WeaverChainSagaStatus status) {
        return sagas
                .list(saga ->
                        saga.gameId().equals(gameId) && saga.playerId().equals(playerId) && saga.status() == status)
                .stream()
                .findFirst();
    }
}
