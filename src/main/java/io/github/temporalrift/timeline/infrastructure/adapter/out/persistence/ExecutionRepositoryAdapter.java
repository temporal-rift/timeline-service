package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.execution.EntropyVersion;
import io.github.temporalrift.timeline.domain.execution.Execution;
import io.github.temporalrift.timeline.domain.execution.ExecutionContext;
import io.github.temporalrift.timeline.domain.execution.Seed;
import io.github.temporalrift.timeline.domain.execution.SimulationSeat;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.ExecutionRepository;

@Component
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class ExecutionRepositoryAdapter implements ExecutionRepository {

    private static final String SELECT = """
            SELECT case_key, seed, entropy_version, manifest_digest, configured_logical_time, logical_time, revision
            FROM simulation_execution WHERE id = 1
            """;

    private static final String REVISION = "revision";

    private final JdbcClient jdbc;

    ExecutionRepositoryAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Execution> find() {
        return jdbc.sql(SELECT).query(this::toExecution).optional();
    }

    @Override
    public Optional<Execution> findWithLock() {
        return jdbc.sql(SELECT + " FOR UPDATE").query(this::toExecution).optional();
    }

    @Override
    public boolean insertIfAbsent(Execution execution) {
        var context = execution.context();
        int inserted = jdbc.sql("""
                        INSERT INTO simulation_execution
                            (id, case_key, seed, entropy_version, manifest_digest,
                             configured_logical_time, logical_time, revision)
                        VALUES (1, :caseKey, :seed, :entropyVersion, :manifestDigest,
                                :configuredLogicalTime, :logicalTime, :revision)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("caseKey", context.caseKey())
                .param("seed", context.seed().decimal())
                .param("entropyVersion", context.entropyVersion().name())
                .param("manifestDigest", context.manifestDigest())
                .param("configuredLogicalTime", utc(context.logicalTime()))
                .param("logicalTime", utc(execution.logicalTime()))
                .param(REVISION, execution.revision())
                .update();
        if (inserted == 0) {
            return false;
        }
        context.seats()
                .forEach(seat -> jdbc.sql("""
                                INSERT INTO simulation_seat (seat_index, player_id, faction)
                                VALUES (:seatIndex, :playerId, :faction)
                                """)
                        .param("seatIndex", seat.seatIndex())
                        .param("playerId", seat.playerId())
                        .param("faction", seat.faction().name())
                        .update());
        return true;
    }

    @Override
    public void update(Execution execution) {
        jdbc.sql("UPDATE simulation_execution SET logical_time = :logicalTime, revision = :revision WHERE id = 1")
                .param("logicalTime", utc(execution.logicalTime()))
                .param(REVISION, execution.revision())
                .update();
    }

    private Execution toExecution(ResultSet rs, int row) throws SQLException {
        var context = new ExecutionContext(
                rs.getObject("case_key", UUID.class),
                new Seed(rs.getString("seed")),
                EntropyVersion.valueOf(rs.getString("entropy_version")),
                rs.getString("manifest_digest"),
                instant(rs, "configured_logical_time"),
                seats());
        return new Execution(context, rs.getLong(REVISION), instant(rs, "logical_time"));
    }

    private List<SimulationSeat> seats() {
        return jdbc.sql("SELECT seat_index, player_id, faction FROM simulation_seat ORDER BY seat_index")
                .query((rs, row) -> new SimulationSeat(
                        rs.getInt("seat_index"),
                        rs.getObject("player_id", UUID.class),
                        MemberFaction.valueOf(rs.getString("faction"))))
                .list();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
