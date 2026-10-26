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
import org.springframework.context.annotation.Profile;
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
@Profile("!in-memory")
class ExecutionRepositoryAdapter implements ExecutionRepository {

    private static final String SELECT = """
            SELECT case_key, seed, entropy_version, manifest_digest, rules, rules_digest, configured_logical_time,
                   logical_time, revision, game_id
            FROM simulation_execution WHERE id = 1
            """;

    private static final String REVISION = "revision";
    private static final String CASE_KEY = "case_key";
    private static final String GAME_ID = "game_id";
    private static final String CASE_KEY_PARAM = "caseKey";
    private static final String GAME_ID_PARAM = "gameId";

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
                            (id, case_key, seed, entropy_version, manifest_digest, rules, rules_digest,
                             configured_logical_time, logical_time, revision)
                        VALUES (1, :caseKey, :seed, :entropyVersion, :manifestDigest, :rules, :rulesDigest,
                                :configuredLogicalTime, :logicalTime, :revision)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param(CASE_KEY_PARAM, context.caseKey())
                .param("seed", context.seed().decimal())
                .param("entropyVersion", context.entropyVersion().name())
                .param("manifestDigest", context.manifestDigest())
                .param("rules", context.rules().canonicalJson())
                .param("rulesDigest", context.rulesDigest())
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

    @Override
    public Optional<Bounds> findBoundsForScope() {
        return jdbc.sql("SELECT case_key, game_id FROM simulation_execution WHERE id = 1 FOR SHARE")
                .query((rs, row) -> new Bounds(rs.getObject(CASE_KEY, UUID.class), rs.getObject(GAME_ID, UUID.class)))
                .optional();
    }

    @Override
    public Optional<Bounds> findBounds() {
        return jdbc.sql("SELECT case_key, game_id FROM simulation_execution WHERE id = 1")
                .query((rs, row) -> new Bounds(rs.getObject(CASE_KEY, UUID.class), rs.getObject(GAME_ID, UUID.class)))
                .optional();
    }

    @Override
    public boolean bindGame(UUID caseKey, UUID gameId) {
        return jdbc.sql("""
                        UPDATE simulation_execution SET game_id = :gameId
                        WHERE id = 1 AND case_key = :caseKey AND game_id IS NULL
                        """)
                        .param(GAME_ID_PARAM, gameId)
                        .param(CASE_KEY_PARAM, caseKey)
                        .update()
                == 1;
    }

    @Override
    public boolean isClosedGame(UUID gameId) {
        return jdbc.sql("SELECT count(*) FROM simulation_closed_game WHERE game_id = :gameId")
                        .param(GAME_ID_PARAM, gameId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    @Override
    public void forgetClosedGames(UUID caseKey) {
        jdbc.sql("DELETE FROM simulation_closed_game WHERE case_key = :caseKey")
                .param(CASE_KEY_PARAM, caseKey)
                .update();
    }

    @Override
    public void close(UUID caseKey) {
        jdbc.sql("""
                        INSERT INTO simulation_closed_game (game_id, case_key)
                        SELECT game_id, case_key FROM simulation_execution WHERE id = 1 AND game_id IS NOT NULL
                        ON CONFLICT (game_id) DO NOTHING
                        """).update();
        jdbc.sql("DELETE FROM simulation_seat").update();
        jdbc.sql("DELETE FROM simulation_execution WHERE id = 1").update();
        jdbc.sql("""
                        INSERT INTO simulation_closed_execution (id, case_key) VALUES (1, :caseKey)
                        ON CONFLICT (id) DO UPDATE SET case_key = EXCLUDED.case_key
                        """).param(CASE_KEY_PARAM, caseKey).update();
    }

    @Override
    public Optional<UUID> lastClosedCaseKey() {
        return jdbc.sql("SELECT case_key FROM simulation_closed_execution WHERE id = 1")
                .query(UUID.class)
                .optional();
    }

    private Execution toExecution(ResultSet rs, int row) throws SQLException {
        var context = new ExecutionContext(
                rs.getObject(CASE_KEY, UUID.class),
                new Seed(rs.getString("seed")),
                EntropyVersion.valueOf(rs.getString("entropy_version")),
                rs.getString("manifest_digest"),
                RulesJson.parse(rs.getString("rules")),
                rs.getString("rules_digest"),
                instant(rs, "configured_logical_time"),
                seats());
        return new Execution(
                context, rs.getLong(REVISION), instant(rs, "logical_time"), rs.getObject(GAME_ID, UUID.class));
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
