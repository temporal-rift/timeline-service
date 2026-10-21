package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;

@Component
@Profile("in-memory")
class InMemoryGameplayDiscard implements GameplayDiscard {

    private final InMemoryDatabase database;

    InMemoryGameplayDiscard(InMemoryDatabase database) {
        this.database = database;
    }

    @Override
    public boolean isClean() {
        return !database.hasRowsOf(table -> !table.startsWith("simulation_"));
    }

    @Override
    public void discardAll() {
        database.deleteAllRowsOf(table -> !table.startsWith("simulation_"));
    }
}
