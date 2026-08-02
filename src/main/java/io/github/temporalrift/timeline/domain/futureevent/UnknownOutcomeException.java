package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** A {@link ProbabilityShift} named an {@code outcomeId} that is not one of the target event's outcomes. */
public class UnknownOutcomeException extends RuntimeException {

    public UnknownOutcomeException(UUID eventId, UUID outcomeId) {
        super("FutureEvent " + eventId + " has no outcome " + outcomeId);
    }
}
