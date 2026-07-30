package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

public class FutureEventAlreadyResolvedException extends RuntimeException {

    public FutureEventAlreadyResolvedException(UUID eventId) {
        super("FutureEvent " + eventId + " is already resolved");
    }
}
