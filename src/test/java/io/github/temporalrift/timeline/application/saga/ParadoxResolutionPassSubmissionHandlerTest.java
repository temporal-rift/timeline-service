package io.github.temporalrift.timeline.application.saga;

import static org.mockito.BDDMockito.then;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;

@ExtendWith(MockitoExtension.class)
class ParadoxResolutionPassSubmissionHandlerTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final int ERA_NUMBER = 2;

    @Mock
    ParadoxResolutionSagaImpl saga;

    @Test
    void pass_recordsACardlessSubmissionThroughTheSagasSubmissionBranch() {
        var handler = new ParadoxResolutionPassSubmissionHandler(saga);

        handler.pass(GAME_ID, ERA_NUMBER, PLAYER_ID);

        then(saga)
                .should()
                .handlePlayerSubmitted(
                        GAME_ID, ERA_NUMBER, new Submission(PLAYER_ID, Submission.PASS, null, null, null));
    }
}
