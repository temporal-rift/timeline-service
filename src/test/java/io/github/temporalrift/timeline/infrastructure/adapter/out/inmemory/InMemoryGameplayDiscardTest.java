package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryGameplayDiscardTest {

    @Test
    @DisplayName("discard removes gameplay rows and keeps the simulation control state")
    void discard_removesOnlyGameplayState() {
        var database = new InMemoryDatabase();
        var gameplay = database.<String, String>table("game_membership");
        var execution = database.<Integer, String>table(TableNames.SIMULATION_EXECUTION);
        var discard = new InMemoryGameplayDiscard(database);

        assertThat(discard.isClean()).isTrue();
        gameplay.put("game", "row");
        execution.put(1, "control");
        assertThat(discard.isClean()).isFalse();

        discard.discardAll();

        assertThat(discard.isClean()).isTrue();
        assertThat(execution.find(1)).contains("control");
    }
}
