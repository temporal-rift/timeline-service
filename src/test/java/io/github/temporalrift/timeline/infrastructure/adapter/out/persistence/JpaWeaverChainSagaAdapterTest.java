package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.port.out.WeaverChainSagaRepository;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaState;
import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaWeaverChainSagaAdapter.class})
class JpaWeaverChainSagaAdapterTest {

    @Autowired
    WeaverChainSagaRepository sagas;

    @Test
    void save_thenFindByChainId_roundTripsOrchestrationFlags() {
        var chainId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        sagas.save(new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, true, 2));

        var state = sagas.findByChainId(chainId).orElseThrow();

        assertThat(state.gameId()).isEqualTo(gameId);
        assertThat(state.playerId()).isEqualTo(playerId);
        assertThat(state.status()).isEqualTo(WeaverChainSagaStatus.OPEN);
        assertThat(state.tapestryProtected()).isTrue();
        assertThat(state.tapestryUsedEra()).isEqualTo(2);
    }

    @Test
    void findOpenByGameAndPlayer_returnsOnlyTheOpenSaga() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var openChain = UUID.randomUUID();
        var brokenChain = UUID.randomUUID();
        sagas.save(new WeaverChainSagaState(openChain, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null));
        sagas.save(new WeaverChainSagaState(brokenChain, gameId, playerId, WeaverChainSagaStatus.BROKEN, false, null));

        var open = sagas.findOpenByGameAndPlayer(gameId, playerId).orElseThrow();

        assertThat(open.chainId()).isEqualTo(openChain);
    }

    @Test
    void findOpenByGameAndPlayer_noOpenSaga_returnsEmpty() {
        assertThat(sagas.findOpenByGameAndPlayer(UUID.randomUUID(), UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void findOpenByGame_returnsEveryOpenSagaInThatGame() {
        var gameId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        sagas.save(new WeaverChainSagaState(first, gameId, UUID.randomUUID(), WeaverChainSagaStatus.OPEN, false, null));
        sagas.save(
                new WeaverChainSagaState(second, gameId, UUID.randomUUID(), WeaverChainSagaStatus.OPEN, false, null));
        sagas.save(new WeaverChainSagaState(
                UUID.randomUUID(), gameId, UUID.randomUUID(), WeaverChainSagaStatus.ENDED, false, null));

        assertThat(sagas.findOpenByGame(gameId))
                .extracting(WeaverChainSagaState::chainId)
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void save_overwritesStatusForRestartResume() {
        var chainId = UUID.randomUUID();
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        sagas.save(new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, true, 2));

        sagas.save(new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, 2));

        var state = sagas.findByChainId(chainId).orElseThrow();
        assertThat(state.tapestryProtected()).isFalse();
        assertThat(sagas.findOpenByGameAndPlayer(gameId, playerId)).isPresent();
    }
}
