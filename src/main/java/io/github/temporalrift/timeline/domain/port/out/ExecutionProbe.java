package io.github.temporalrift.timeline.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/** This service's view of the hosted game and its pending work, aggregated into the execution checkpoint. */
public interface ExecutionProbe {

    Observation observe(Instant now);

    /** Records accepted for publication but not yet relayed to the broker. */
    int pendingPublications();

    /**
     * @param gameId the game this lane hosts, or {@code null} before any game started
     * @param gameEnded whether this service has completed the hosted game's end
     * @param dueTimers unhandled timers whose deadline is at or before {@code now}
     * @param nextDeadline earliest unhandled deadline after {@code now}, or {@code null}
     */
    record Observation(UUID gameId, boolean gameEnded, int dueTimers, Instant nextDeadline) {}
}
