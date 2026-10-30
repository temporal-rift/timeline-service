package io.github.temporalrift.timeline.infrastructure.adapter.out.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.port.out.GameScope.InboundRecord;

class EventFenceTest {

    @Test
    @DisplayName("an ordinary deployment admits every game and fences nothing")
    void unboundedScopeAndFence_admitEverything() {
        var scope = new AllGamesScope();
        var fence = new UnboundedEventFence();

        assertThat(scope.admits(new InboundRecord("game.events", 0, 1L, UUID.randomUUID())))
                .isTrue();
        assertThat(scope.admits(new InboundRecord(null, null, null, null))).isTrue();
        assertThat(fence.isFenced("game.events", 0, 1L)).isFalse();
        fence.capture();
    }

    @Test
    @DisplayName("the in-memory runtime has no broker offsets to fence")
    void inMemoryFence_fencesNothing() {
        var fence = new InMemoryEventFence();

        assertThat(fence.isFenced("game.events", 0, 1L)).isFalse();
        fence.capture();
    }
}
