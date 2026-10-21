package io.github.temporalrift.timeline.infrastructure.adapter.out.context;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.ExecutionRules;

/** Serves the rules of the open execution context to the deployment's rule ports. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class ExecutionContextRules implements ExecutionRules {

    private final PinnedExecutionContext pinned;

    ExecutionContextRules(PinnedExecutionContext pinned) {
        this.pinned = pinned;
    }

    @Override
    public Optional<Rules> current() {
        return pinned.currentRules();
    }
}
