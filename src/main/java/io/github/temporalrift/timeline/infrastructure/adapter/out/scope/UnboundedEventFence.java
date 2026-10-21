package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EventFence;

/** An ordinary deployment reads one continuous game stream, so no record is ever fenced. */
@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "false", matchIfMissing = true)
class UnboundedEventFence implements EventFence {

    @Override
    public boolean isFenced(String topic, int partition, long offset) {
        return false;
    }

    @Override
    public void capture() {
        // Nothing is ever fenced.
    }
}
