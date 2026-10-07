package io.github.temporalrift.timeline.infrastructure.adapter.out.entropy;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

/** The configured context never changes once accepted, so it is read once and then served from memory. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class PinnedExecutionContext {

    private final ExecutionRepository executions;
    private final AtomicReference<ExecutionContext> context = new AtomicReference<>();

    PinnedExecutionContext(ExecutionRepository executions) {
        this.executions = executions;
    }

    ExecutionContext require() {
        var pinned = context.get();
        if (pinned == null) {
            pinned = executions.find().map(Execution::context).orElseThrow(ExecutionNotConfiguredException::new);
            context.set(pinned);
        }
        return pinned;
    }
}
