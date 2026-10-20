package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static io.github.temporalrift.timeline.infrastructure.adapter.out.persistence.ExecutionRepositoryAdapter.utc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.ExecutionProbe;

@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
@Profile("!in-memory")
class ExecutionProbeAdapter implements ExecutionProbe {

    // The claim the lifecycle consumer records once its GameEnded cleanup has committed.
    private static final String GAME_ENDED_CONSUMER = "futureevent.game-ended";

    private final JdbcClient jdbc;

    ExecutionProbeAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Observation observe(Instant now) {
        var gameId = jdbc.sql("SELECT game_id FROM simulation_execution WHERE id = 1")
                .query(UUID.class)
                .optional()
                .orElse(null);
        var gameEnded = jdbc.sql("SELECT count(*) FROM processed_events WHERE consumer = :consumer")
                        .param("consumer", GAME_ENDED_CONSUMER)
                        .query(Integer.class)
                        .single()
                > 0;
        if (gameId == null) {
            return new Observation(null, false, 0, null);
        }
        var dueTimers = jdbc.sql("""
                        SELECT count(*) FROM paradox_resolution_phase
                        WHERE game_id = :gameId AND status = 'WAITING' AND timer_expires_at <= :now
                        """)
                .param("gameId", gameId)
                .param("now", utc(now))
                .query(Integer.class)
                .single();
        var nextDeadline = jdbc.sql("""
                        SELECT timer_expires_at FROM paradox_resolution_phase
                        WHERE game_id = :gameId AND status = 'WAITING' AND timer_expires_at > :now
                        ORDER BY timer_expires_at LIMIT 1
                        """)
                .param("gameId", gameId)
                .param("now", utc(now))
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
        return new Observation(gameId, gameEnded, dueTimers, nextDeadline);
    }

    @Override
    public int pendingPublications() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status <> 'SENT'")
                .query(Integer.class)
                .single();
    }
}
