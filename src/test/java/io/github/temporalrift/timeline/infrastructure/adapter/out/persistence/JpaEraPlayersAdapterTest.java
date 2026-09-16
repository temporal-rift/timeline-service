package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.TimelineServiceApplication;
import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TimelineServiceApplication.class)
@Import({
    TestcontainersConfiguration.class,
    JpaEraPlayersAdapterTest.ObjectMapperTestConfig.class,
    JpaEraPlayersAdapter.class
})
class JpaEraPlayersAdapterTest {

    static class ObjectMapperTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }
    }

    @Autowired
    EraPlayersPort eraPlayers;

    @Test
    void save_thenFind_returnsTheSamePlayerIds() {
        var gameId = UUID.randomUUID();
        var playerId1 = UUID.randomUUID();
        var playerId2 = UUID.randomUUID();

        eraPlayers.save(gameId, 1, List.of(playerId1, playerId2));

        assertThat(eraPlayers.find(gameId, 1)).contains(List.of(playerId1, playerId2));
    }

    @Test
    void find_noRowForGameAndEra_returnsEmptyOptional() {
        assertThat(eraPlayers.find(UUID.randomUUID(), 1)).isEmpty();
    }

    @Test
    void find_rowWithNoPlayers_returnsPresentEmptyRoster() {
        var gameId = UUID.randomUUID();
        eraPlayers.save(gameId, 1, List.of());

        assertThat(eraPlayers.find(gameId, 1)).contains(List.of());
    }

    @Test
    void find_doesNotReturnAnotherEraOfTheSameGame() {
        var gameId = UUID.randomUUID();
        var era1PlayerId = UUID.randomUUID();
        var era2PlayerId = UUID.randomUUID();
        eraPlayers.save(gameId, 1, List.of(era1PlayerId));
        eraPlayers.save(gameId, 2, List.of(era2PlayerId));

        assertThat(eraPlayers.find(gameId, 1)).contains(List.of(era1PlayerId));
        assertThat(eraPlayers.find(gameId, 2)).contains(List.of(era2PlayerId));
    }

    @Test
    void findLatestEraNumber_multipleErasSaved_returnsTheHighest() {
        var gameId = UUID.randomUUID();
        eraPlayers.save(gameId, 1, List.of());
        eraPlayers.save(gameId, 3, List.of());
        eraPlayers.save(gameId, 2, List.of());

        assertThat(eraPlayers.findLatestEraNumber(gameId)).contains(3);
    }

    @Test
    void findLatestEraNumber_noEraStarted_returnsEmptyOptional() {
        assertThat(eraPlayers.findLatestEraNumber(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findLatestEraNumber_doesNotConsiderAnotherGame() {
        var gameId1 = UUID.randomUUID();
        var gameId2 = UUID.randomUUID();
        eraPlayers.save(gameId1, 1, List.of());
        eraPlayers.save(gameId2, 5, List.of());

        assertThat(eraPlayers.findLatestEraNumber(gameId1)).contains(1);
    }
}
