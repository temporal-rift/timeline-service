package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.EntropyDecisionRepository;

@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class EntropyDecisionRepositoryAdapter implements EntropyDecisionRepository {

    private final JdbcClient jdbc;

    EntropyDecisionRepositoryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> find(String purpose, String coordinate, int drawIndex) {
        return jdbc.sql("""
                        SELECT word FROM simulation_entropy_decision
                        WHERE purpose = :purpose AND coordinate = :coordinate AND draw_index = :drawIndex
                        """)
                .param("purpose", purpose)
                .param("coordinate", coordinate)
                .param("drawIndex", drawIndex)
                .query(Long.class)
                .optional();
    }

    @Override
    public void save(String purpose, String coordinate, int drawIndex, long word) {
        jdbc.sql("""
                        INSERT INTO simulation_entropy_decision (purpose, coordinate, draw_index, word)
                        VALUES (:purpose, :coordinate, :drawIndex, :word)
                        ON CONFLICT (purpose, coordinate, draw_index) DO NOTHING
                        """)
                .param("purpose", purpose)
                .param("coordinate", coordinate)
                .param("drawIndex", drawIndex)
                .param("word", word)
                .update();
    }
}
