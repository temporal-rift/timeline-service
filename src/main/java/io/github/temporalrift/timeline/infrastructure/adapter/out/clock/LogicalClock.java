package io.github.temporalrift.timeline.infrastructure.adapter.out.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.LogicalClockControl;

/** The only time source of an isolated deployment: it moves solely when the control interface advances it. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class LogicalClock extends Clock implements LogicalClockControl {

    private final ExecutionRepository executions;
    private volatile Instant current;

    LogicalClock(ExecutionRepository executions) {
        this.executions = executions;
    }

    @Override
    public synchronized Instant instant() {
        if (current == null) {
            // Before configuration no gameplay exists, so nothing may look due. Loading under the same monitor that
            // reset() takes means a close cannot be followed by a thread re-pinning the closed context's time.
            current = executions.find().map(Execution::logicalTime).orElse(Instant.EPOCH);
        }
        return current;
    }

    @Override
    public synchronized void advanceTo(Instant instant) {
        if (current == null || instant.isAfter(current)) {
            current = instant;
        }
    }

    @Override
    public synchronized void reset() {
        current = null;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
