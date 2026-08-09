package io.github.temporalrift.timeline.application.saga;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.PlayParadoxResolutionCardUseCase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;

/** Public entry point into {@code ParadoxResolutionSaga}'s player-submission branch. */
@Service
class ParadoxResolutionCardSubmissionHandler implements PlayParadoxResolutionCardUseCase {

    private final ParadoxResolutionSagaImpl saga;

    ParadoxResolutionCardSubmissionHandler(ParadoxResolutionSagaImpl saga) {
        this.saga = saga;
    }

    @Override
    public void play(
            UUID gameId, int eraNumber, UUID playerId, String cardType, UUID targetEventId, UUID targetOutcomeId) {
        saga.handlePlayerSubmitted(
                gameId, eraNumber, new Submission(playerId, cardType, targetEventId, targetOutcomeId));
    }
}
