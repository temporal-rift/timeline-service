package io.github.temporalrift.timeline.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.timeline.application.port.in.GetChainUseCase;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.v1.ChainsApi;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.v1.model.ChainLink;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.v1.model.ChainResponse;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.v1.model.ChainStatus;
import io.github.temporalrift.timeline.infrastructure.adapter.in.rest.v1.model.PendingChainLink;
import io.github.temporalrift.timeline.shared.CurrentPlayer;

@RestController
class ChainsController implements ChainsApi {

    private final GetChainUseCase getChainUseCase;

    ChainsController(GetChainUseCase getChainUseCase) {
        this.getChainUseCase = getChainUseCase;
    }

    @Override
    public ResponseEntity<ChainResponse> getChain(UUID gameId) {
        var result = getChainUseCase.get(gameId, CurrentPlayer.id());
        var links = result.links().stream()
                .map(link -> new ChainLink(link.eventId(), link.outcomeId(), link.eraNumber()))
                .toList();
        var pendingLink = result.pendingLink() == null
                ? null
                : new PendingChainLink(
                        result.pendingLink().eventId(), result.pendingLink().outcomeId());
        return ResponseEntity.ok(new ChainResponse(
                        result.chainId(), ChainStatus.valueOf(result.status().name()), result.chainLength(), links)
                .pendingLink(pendingLink)
                .protectionArmed(result.protectionArmed()));
    }
}
