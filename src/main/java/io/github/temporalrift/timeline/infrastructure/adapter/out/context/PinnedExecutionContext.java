package io.github.temporalrift.timeline.infrastructure.adapter.out.context;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.ExecutionClosed;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.ExecutionNotConfiguredException;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;

/** The open context never changes while it is open, so it is read once and then served from memory until closed. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
public class PinnedExecutionContext {

    private final ExecutionRepository executions;
    private final AtomicReference<ExecutionContext> context = new AtomicReference<>();
    private final AtomicReference<ExecutionRules.Rules> rules = new AtomicReference<>();
    private volatile long closures;

    public PinnedExecutionContext(ExecutionRepository executions) {
        this.executions = executions;
    }

    public Optional<ExecutionContext> current() {
        var pinned = context.get();
        if (pinned != null) {
            return Optional.of(pinned);
        }
        var seen = closures;
        var loaded = executions.find().map(Execution::context);
        loaded.ifPresent(open -> pin(open, seen));
        return loaded;
    }

    public ExecutionContext require() {
        return current().orElseThrow(ExecutionNotConfiguredException::new);
    }

    /** The open context's flat rules, built once per digest rather than on every rule read. */
    public Optional<ExecutionRules.Rules> currentRules() {
        var open = current();
        if (open.isEmpty()) {
            return Optional.empty();
        }
        var cached = rules.get();
        if (cached == null || !cached.digest().equals(open.get().rulesDigest())) {
            cached = new ExecutionRules.Rules(
                    open.get().rulesDigest(), open.get().rules().bindable());
            rules.set(cached);
        }
        return Optional.of(cached);
    }

    @EventListener
    synchronized void onClosed(ExecutionClosed closed) {
        context.set(null);
        rules.set(null);
        closures++;
    }

    // A context read before a close committed must not be pinned after it.
    private synchronized void pin(ExecutionContext open, long seen) {
        if (closures == seen) {
            context.set(open);
        }
    }
}
