package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.port.out.GameplayDiscard;

@Component
@Profile("!in-memory")
@ConditionalOnProperty(name = "game.simulation.enabled", havingValue = "true")
class GameplayDiscardAdapter implements GameplayDiscard {

    private final JdbcClient jdbc;

    GameplayDiscardAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isClean() {
        var tables = gameplayTables();
        if (tables.isEmpty()) {
            return true;
        }
        var anyRow = tables.stream()
                .map(table -> "SELECT 1 FROM \"" + table + "\"")
                .collect(Collectors.joining(" UNION ALL "));
        return !jdbc.sql("SELECT EXISTS (" + anyRow + ")").query(Boolean.class).single();
    }

    /** Every table of the schema except the control state and the migration history holds gameplay state. */
    @Override
    public void discardAll() {
        var tables = gameplayTables();
        if (!tables.isEmpty()) {
            jdbc.sql("TRUNCATE TABLE "
                            + tables.stream().map(table -> '"' + table + '"').collect(Collectors.joining(", "))
                            + " RESTART IDENTITY CASCADE")
                    .update();
        }
    }

    private List<String> gameplayTables() {
        return jdbc.sql("""
                        SELECT tablename FROM pg_tables
                        WHERE schemaname = current_schema()
                          AND tablename NOT LIKE 'simulation!_%' ESCAPE '!'
                          AND tablename NOT LIKE 'databasechangelog%'
                        ORDER BY tablename
                        """).query(String.class).list();
    }
}
