package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;

@Repository
@Profile("in-memory")
class InMemoryEntropyDecisionRepository implements EntropyDecisionRepository {

    private final Table<Draw, Long> decisions;

    InMemoryEntropyDecisionRepository(InMemoryDatabase database) {
        this.decisions = database.table(TableNames.SIMULATION_ENTROPY_DECISION);
    }

    @Override
    public Optional<Long> find(String purpose, String coordinate, int drawIndex) {
        return decisions.find(new Draw(purpose, coordinate, drawIndex));
    }

    /** Keeps the first word drawn at a position, like {@code ON CONFLICT DO NOTHING}. */
    @Override
    public void save(String purpose, String coordinate, int drawIndex, long word) {
        decisions.insertIfAbsent(new Draw(purpose, coordinate, drawIndex), word);
    }

    private record Draw(String purpose, String coordinate, int drawIndex) {}
}
