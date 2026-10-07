package io.github.temporalrift.timeline.domain.execution;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record ClockAdvance(UUID operationId, long expectedRevision, Instant targetTime) {

    public ClockAdvance {
        if (operationId == null || targetTime == null) {
            throw new InvalidClockAdvanceException("operationId and targetTime are required");
        }
        targetTime = targetTime.truncatedTo(ChronoUnit.MICROS);
        if (expectedRevision < 0) {
            throw new InvalidClockAdvanceException("expectedRevision must not be negative");
        }
    }
}
