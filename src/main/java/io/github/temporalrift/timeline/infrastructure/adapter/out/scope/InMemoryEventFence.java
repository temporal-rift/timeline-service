package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EventFence;

/**
 * The in-memory runtime delivers a producer's records synchronously, so a delivery already in progress is separated
 * from a close by the execution row lock rather than by an offset. Nothing is fenced here.
 */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
@Profile("in-memory")
class InMemoryEventFence implements EventFence {

    @Override
    public boolean isFenced(String topic, int partition, long offset) {
        return false;
    }

    @Override
    public void capture() {
        // No broker offsets exist in this runtime.
    }
}
