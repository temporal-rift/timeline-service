package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import io.github.temporalrift.timeline.TestcontainersConfiguration;
import io.github.temporalrift.timeline.TimelineServiceApplication;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;
import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort.CascadeCarryForward;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TimelineServiceApplication.class)
@Import({TestcontainersConfiguration.class, JpaCascadeCarryForwardAdapter.class})
class JpaCascadeCarryForwardAdapterTest {

    @Autowired
    CascadeCarryForwardPort cascadeCarryForward;

    @Test
    void arm_thenFindByGameAndEra_returnsTheRow() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));
    }

    @Test
    void arm_repeatedForSameEventAndOutcome_isIdempotent() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();

        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1)).hasSize(1);
    }

    @Test
    void arm_differentEventsOrOutcomes_areIndependentRows() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventA = UUID.randomUUID();
        var eventB = UUID.randomUUID();
        var outcomeA = UUID.randomUUID();
        var outcomeB = UUID.randomUUID();

        cascadeCarryForward.arm(gameId, 1, playerId, eventA, outcomeA);
        cascadeCarryForward.arm(gameId, 1, playerId, eventA, outcomeB);
        cascadeCarryForward.arm(gameId, 1, playerId, eventB, outcomeA);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1))
                .containsExactlyInAnyOrder(
                        new CascadeCarryForward(playerId, eventA, outcomeA),
                        new CascadeCarryForward(playerId, eventA, outcomeB),
                        new CascadeCarryForward(playerId, eventB, outcomeA));
    }

    @Test
    void findByGameAndEra_doesNotReturnAnotherEraOfTheSameGame() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var era1EventId = UUID.randomUUID();
        var era2EventId = UUID.randomUUID();
        cascadeCarryForward.arm(gameId, 1, playerId, era1EventId, UUID.randomUUID());
        cascadeCarryForward.arm(gameId, 2, playerId, era2EventId, UUID.randomUUID());

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1))
                .allSatisfy(row -> assertThat(row.eventId()).isEqualTo(era1EventId));
        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 2))
                .allSatisfy(row -> assertThat(row.eventId()).isEqualTo(era2EventId));
    }

    @Test
    void confirm_movesTheRowToTheTargetEraNumber() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);

        cascadeCarryForward.confirm(gameId, 1, eventId, outcomeId, 2);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 2))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));
    }

    @Test
    void confirm_noMatchingRow_isHarmless() {
        var gameId = UUID.randomUUID();

        cascadeCarryForward.confirm(gameId, 1, UUID.randomUUID(), UUID.randomUUID(), 2);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    void delete_removesOnlyThatRow() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeToDelete = UUID.randomUUID();
        var outcomeToKeep = UUID.randomUUID();
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeToDelete);
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeToKeep);

        cascadeCarryForward.delete(gameId, 1, eventId, outcomeToDelete);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeToKeep));
    }

    @Test
    void delete_noMatchingRow_isHarmless() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        cascadeCarryForward.arm(gameId, 1, playerId, eventId, outcomeId);

        cascadeCarryForward.delete(gameId, 1, UUID.randomUUID(), UUID.randomUUID());

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));
    }

    @Test
    void deleteByGame_removesEveryEraForThatGame() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        cascadeCarryForward.arm(gameId, 1, playerId, UUID.randomUUID(), UUID.randomUUID());
        cascadeCarryForward.arm(gameId, 2, playerId, UUID.randomUUID(), UUID.randomUUID());

        cascadeCarryForward.deleteByGame(gameId);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 1)).isEmpty();
        assertThat(cascadeCarryForward.findByGameAndEra(gameId, 2)).isEmpty();
    }

    @Test
    void deleteByGame_doesNotAffectAnotherGame() {
        var gameId1 = UUID.randomUUID();
        var gameId2 = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var outcomeId = UUID.randomUUID();
        cascadeCarryForward.arm(gameId1, 1, playerId, eventId, outcomeId);
        cascadeCarryForward.arm(gameId2, 1, playerId, eventId, outcomeId);

        cascadeCarryForward.deleteByGame(gameId1);

        assertThat(cascadeCarryForward.findByGameAndEra(gameId1, 1)).isEmpty();
        assertThat(cascadeCarryForward.findByGameAndEra(gameId2, 1))
                .containsExactly(new CascadeCarryForward(playerId, eventId, outcomeId));
    }
}
