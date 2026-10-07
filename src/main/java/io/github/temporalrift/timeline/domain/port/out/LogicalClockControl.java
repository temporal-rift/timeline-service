package io.github.temporalrift.timeline.domain.port.out;

import java.time.Instant;

public interface LogicalClockControl {

    /** Moves the logical clock forward; an earlier instant leaves it unchanged. */
    void advanceTo(Instant instant);
}
