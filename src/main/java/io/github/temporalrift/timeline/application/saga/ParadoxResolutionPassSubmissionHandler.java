package io.github.temporalrift.timeline.application.saga;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.PassParadoxResolutionUseCase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;

/** Public entry point into {@code ParadoxResolutionSaga}'s player-submission branch for an explicit pass. */
@Service
class ParadoxResolutionPassSubmissionHandler implements PassParadoxResolutionUseCase {

    private final ParadoxResolutionSagaImpl saga;

    ParadoxResolutionPassSubmissionHandler(ParadoxResolutionSagaImpl saga) {
        this.saga = saga;
    }

    @Override
    public void pass(UUID gameId, int eraNumber, UUID playerId) {
        saga.handlePlayerSubmitted(gameId, eraNumber, Submission.pass(playerId));
    }
}
