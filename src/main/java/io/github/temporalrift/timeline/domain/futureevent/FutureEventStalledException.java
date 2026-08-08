package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

public class FutureEventStalledException extends RuntimeException {

    public FutureEventStalledException(UUID eventId) {
        super("FutureEvent " + eventId + " is stalled and cannot be resolved this era");
    }
}
