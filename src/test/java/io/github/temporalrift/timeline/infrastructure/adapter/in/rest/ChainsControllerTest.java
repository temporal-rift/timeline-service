package io.github.temporalrift.timeline.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.temporalrift.timeline.TestSecurityConfig;
import io.github.temporalrift.timeline.application.port.in.GetChainUseCase;
import io.github.temporalrift.timeline.domain.membership.NotGameParticipantException;
import io.github.temporalrift.timeline.domain.membership.NotWeaverException;
import io.github.temporalrift.timeline.domain.weaverchain.ChainLink;
import io.github.temporalrift.timeline.domain.weaverchain.ChainNotFoundException;
import io.github.temporalrift.timeline.domain.weaverchain.ChainStatus;
import io.github.temporalrift.timeline.infrastructure.config.PlayerAuthenticationToken;
import io.github.temporalrift.timeline.infrastructure.config.SecurityConfig;
import io.github.temporalrift.timeline.shared.PlayerPrincipal;

@WebMvcTest(ChainsController.class)
@Import({SecurityConfig.class, TestSecurityConfig.class})
class ChainsControllerTest {

    static final UUID PLAYER_ID = UUID.randomUUID();
    static final UUID GAME_ID = UUID.randomUUID();
    static final UUID CHAIN_ID = UUID.randomUUID();
    static final UUID EVENT_ID = UUID.randomUUID();
    static final UUID OUTCOME_ID = UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GetChainUseCase getChainUseCase;

    private RequestPostProcessor auth() {
        return authentication(new PlayerAuthenticationToken(new PlayerPrincipal(PLAYER_ID)));
    }

    @Test
    @DisplayName("unauthenticated — rejected before any chain lookup")
    void getChain_unauthenticated_rejected() throws Exception {
        mockMvc.perform(get("/api/v1/games/{gameId}/chains", GAME_ID)).andExpect(status().isUnauthorized());

        then(getChainUseCase).should(never()).get(any(), any());
    }

    @Test
    @DisplayName("weaver with a chain — returns chain state")
    void getChain_weaverWithChain_returnsChainState() throws Exception {
        var sourceEventId = UUID.randomUUID();
        var sourceOutcomeId = UUID.randomUUID();
        given(getChainUseCase.get(GAME_ID, PLAYER_ID))
                .willReturn(new GetChainUseCase.Result(
                        CHAIN_ID,
                        ChainStatus.ACTIVE,
                        1,
                        List.of(new ChainLink(EVENT_ID, OUTCOME_ID, 2, sourceEventId, sourceOutcomeId)),
                        true));

        mockMvc.perform(get("/api/v1/games/{gameId}/chains", GAME_ID).with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainId").value(CHAIN_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.chainLength").value(1))
                .andExpect(jsonPath("$.links[0].eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.links[0].outcomeId").value(OUTCOME_ID.toString()))
                .andExpect(jsonPath("$.links[0].eraNumber").value(2))
                .andExpect(jsonPath("$.links[0].sourceEventId").value(sourceEventId.toString()))
                .andExpect(jsonPath("$.links[0].sourceOutcomeId").value(sourceOutcomeId.toString()))
                .andExpect(jsonPath("$.protectionArmed").value(true));

        then(getChainUseCase).should().get(GAME_ID, PLAYER_ID);
    }

    @Test
    @DisplayName("non-Weaver participant — 403 with stable code")
    void getChain_nonWeaver_forbidden() throws Exception {
        given(getChainUseCase.get(GAME_ID, PLAYER_ID)).willThrow(new NotWeaverException());

        mockMvc.perform(get("/api/v1/games/{gameId}/chains", GAME_ID).with(auth()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403-01"));
    }

    @Test
    @DisplayName("non-participant — 404 with stable code")
    void getChain_nonParticipant_notFound() throws Exception {
        given(getChainUseCase.get(GAME_ID, PLAYER_ID)).willThrow(new NotGameParticipantException(GAME_ID));

        mockMvc.perform(get("/api/v1/games/{gameId}/chains", GAME_ID).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404-01"));
    }

    @Test
    @DisplayName("weaver without a chain — 404 with stable code")
    void getChain_weaverWithoutChain_notFound() throws Exception {
        given(getChainUseCase.get(GAME_ID, PLAYER_ID)).willThrow(new ChainNotFoundException(GAME_ID, PLAYER_ID));

        mockMvc.perform(get("/api/v1/games/{gameId}/chains", GAME_ID).with(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404-02"));
    }
}
