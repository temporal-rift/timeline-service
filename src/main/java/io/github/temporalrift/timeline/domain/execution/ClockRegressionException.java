package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;

public class ClockRegressionException extends RuntimeException {

    public ClockRegressionException(Instant target, Instant current) {
        super("Target time " + target + " precedes the current logical time " + current);
    }
}
