package io.github.temporalrift.timeline.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.application.port.in.GetChainUseCase;
import io.github.temporalrift.timeline.domain.event.ChainLinkAdded;
import io.github.temporalrift.timeline.domain.event.WeaverChainStarted;
import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
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

@ExtendWith(MockitoExtension.class)
class GetChainQueryHandlerTest {

    @Mock
    GameMembershipPort memberships;

    @Mock
    WeaverChainSagaRepository sagas;

    @Mock
    WeaverChainRepository chains;

    @InjectMocks
    GetChainQueryHandler handler;

    @Test
    @DisplayName("weaver with a chain — returns chain state with links")
    void get_weaverWithChain_returnsChainState() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        givenMembership(gameId, playerId, MemberFaction.WEAVERS);
        given(sagas.findAllByGameAndPlayer(gameId, playerId)).willReturn(List.of(openSaga(chainId, gameId, playerId)));
        given(chains.findById(chainId))
                .willReturn(WeaverChain.replay(
                        chainId,
                        List.of(
                                new WeaverChainStarted(chainId, playerId, gameId),
                                new ChainLinkAdded(chainId, eventId, outcomeId, 1))));

        GetChainUseCase.Result result = handler.get(gameId, playerId);

        assertThat(result.chainId()).isEqualTo(chainId);
        assertThat(result.chainLength()).isEqualTo(1);
        assertThat(result.links()).hasSize(1);
        assertThat(result.links().getFirst().eventId()).isEqualTo(eventId);
        assertThat(result.links().getFirst().outcomeId()).isEqualTo(outcomeId);
        assertThat(result.links().getFirst().eraNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("open chain wins over an older terminal one")
    void get_openAndTerminalChains_returnsOpenChain() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var openChainId = UUID.randomUUID();
        var brokenChainId = UUID.randomUUID();
        givenMembership(gameId, playerId, MemberFaction.WEAVERS);
        given(sagas.findAllByGameAndPlayer(gameId, playerId))
                .willReturn(List.of(
                        new WeaverChainSagaState(
                                brokenChainId, gameId, playerId, WeaverChainSagaStatus.BROKEN, false, null),
                        openSaga(openChainId, gameId, playerId)));
        given(chains.findById(brokenChainId))
                .willReturn(WeaverChain.replay(
                        brokenChainId, List.of(new WeaverChainStarted(brokenChainId, playerId, gameId))));
        given(chains.findById(openChainId))
                .willReturn(WeaverChain.replay(
                        openChainId, List.of(new WeaverChainStarted(openChainId, playerId, gameId))));

        GetChainUseCase.Result result = handler.get(gameId, playerId);

        assertThat(result.chainId()).isEqualTo(openChainId);
    }

    @Test
    @DisplayName("non-Weaver participant — refused without touching sagas or chains")
    void get_nonWeaverParticipant_refused() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        givenMembership(gameId, playerId, MemberFaction.ERASERS);

        assertThatThrownBy(() -> handler.get(gameId, playerId)).isInstanceOf(NotWeaverException.class);

        verifyNoInteractions(sagas, chains);
    }

    @Test
    @DisplayName("non-participant — not found without touching sagas or chains")
    void get_nonParticipant_notFound() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        given(memberships.find(gameId, playerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> handler.get(gameId, playerId)).isInstanceOf(NotGameParticipantException.class);

        verifyNoInteractions(sagas, chains);
    }

    @Test
    @DisplayName("weaver without a saga — chain not found")
    void get_weaverWithoutSaga_chainNotFound() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        givenMembership(gameId, playerId, MemberFaction.WEAVERS);
        given(sagas.findAllByGameAndPlayer(gameId, playerId)).willReturn(List.of());

        assertThatThrownBy(() -> handler.get(gameId, playerId)).isInstanceOf(ChainNotFoundException.class);

        verifyNoInteractions(chains);
    }

    @Test
    @DisplayName("saga without a stream — chain not found")
    void get_sagaWithoutStream_chainNotFound() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var chainId = UUID.randomUUID();
        givenMembership(gameId, playerId, MemberFaction.WEAVERS);
        given(sagas.findAllByGameAndPlayer(gameId, playerId)).willReturn(List.of(openSaga(chainId, gameId, playerId)));
        given(chains.findById(chainId)).willThrow(new WeaverChainNotFoundException(chainId));

        assertThatThrownBy(() -> handler.get(gameId, playerId)).isInstanceOf(ChainNotFoundException.class);
    }

    private void givenMembership(UUID gameId, UUID playerId, MemberFaction faction) {
        given(memberships.find(gameId, playerId))
                .willReturn(Optional.of(new GameMembership(gameId, playerId, faction)));
    }

    private static WeaverChainSagaState openSaga(UUID chainId, UUID gameId, UUID playerId) {
        return new WeaverChainSagaState(chainId, gameId, playerId, WeaverChainSagaStatus.OPEN, false, null);
    }
}
