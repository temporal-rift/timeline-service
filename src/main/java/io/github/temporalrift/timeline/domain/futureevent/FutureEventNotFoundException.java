package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

public class FutureEventNotFoundException extends RuntimeException {

    public FutureEventNotFoundException(UUID eventId) {
        super("FutureEvent " + eventId + " not found");
    }
}
