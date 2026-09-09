package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.domain.futureevent.CardGrade;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.ActionKind;
import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort.BufferedAction;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestcontainersConfiguration.class,
    JpaRoundActionBufferAdapterTest.ObjectMapperTestConfig.class,
    JpaRoundActionBufferAdapter.class
})
class JpaRoundActionBufferAdapterTest {

    static class ObjectMapperTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }
    }

    @Autowired
    RoundActionBufferPort buffer;

    @Autowired
    RoundActionBufferJpaRepository jpaRepository;

    @Test
    void save_listModeScan_roundTripsTargetEventIds() {
        var gameId = UUID.randomUUID();
        var eventId1 = UUID.randomUUID();
        var eventId2 = UUID.randomUUID();
        var action = scanAction(List.of(eventId1, eventId2));

        buffer.save(gameId, 1, 1, action);

        var found = buffer.findByRound(gameId, 1, 1);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().targetEventIds()).containsExactly(eventId1, eventId2);
        assertThat(found.getFirst().targetEventId()).isNull();
    }

    @Test
    void save_scalarModeAction_leavesTargetEventIdsEmpty() {
        var gameId = UUID.randomUUID();
        var targetEventId = UUID.randomUUID();
        var action = new BufferedAction(
                ActionKind.CARD_PLAYED,
                "PUSH",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                targetEventId,
                null,
                null,
                UUID.randomUUID(),
                null,
                CardGrade.II,
                Instant.now(),
                UUID.randomUUID());

        buffer.save(gameId, 1, 1, action);

        var found = buffer.findByRound(gameId, 1, 1);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().targetEventIds()).isEmpty();
        assertThat(found.getFirst().targetEventId()).isEqualTo(targetEventId);
    }

    @Test
    void findByRound_persistedTargetEventIdsContainsNull_dropsTheNullEntryInstead() {
        var gameId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var entity = new RoundActionBufferEntity(
                new RoundKey(gameId, 1, 1),
                ActionKind.CARD_PLAYED,
                "SCAN",
                null,
                CardGrade.I,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "[\"" + eventId + "\", null]",
                null,
                null,
                null,
                Instant.now(),
                UUID.randomUUID());
        jpaRepository.save(entity);

        var found = buffer.findByRound(gameId, 1, 1);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().targetEventIds()).containsExactly(eventId);
    }

    private static BufferedAction scanAction(List<UUID> targetEventIds) {
        return new BufferedAction(
                ActionKind.CARD_PLAYED,
                "SCAN",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                targetEventIds,
                null,
                null,
                null,
                CardGrade.II,
                Instant.now(),
                UUID.randomUUID());
    }
}
