package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventFenceTest {

    @Test
    @DisplayName("the in-memory runtime has no broker offsets to fence")
    void inMemoryFence_fencesNothing() {
        var fence = new InMemoryEventFence();

        assertThat(fence.isFenced("game.events", 0, 1L)).isFalse();
        fence.capture();
    }
}
