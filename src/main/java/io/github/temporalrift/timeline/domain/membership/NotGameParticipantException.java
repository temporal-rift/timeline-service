package io.github.temporalrift.timeline.domain.membership;

import java.util.UUID;

/** The caller is not a participant of the game — indistinguishable from the game not existing. */
public class NotGameParticipantException extends RuntimeException {

    public NotGameParticipantException(UUID gameId) {
        super("Game " + gameId + " not found");
    }
}
