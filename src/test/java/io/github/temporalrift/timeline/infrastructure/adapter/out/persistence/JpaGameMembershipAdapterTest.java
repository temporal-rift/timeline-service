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
import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TimelineServiceApplication.class)
@Import({TestcontainersConfiguration.class, JpaGameMembershipAdapter.class})
class JpaGameMembershipAdapterTest {

    @Autowired
    GameMembershipPort memberships;

    @Test
    void save_thenFind_returnsTheSameMembership() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();

        memberships.save(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));

        assertThat(memberships.find(gameId, playerId))
                .contains(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));
    }

    @Test
    void find_noRowForGameAndPlayer_returnsEmptyOptional() {
        assertThat(memberships.find(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void save_twiceForSameGameAndPlayer_overwritesFaction() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        memberships.save(new GameMembership(gameId, playerId, MemberFaction.ERASERS));

        memberships.save(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));

        assertThat(memberships.find(gameId, playerId))
                .contains(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));
    }

    @Test
    void find_doesNotReturnAnotherGameOrPlayer() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        memberships.save(new GameMembership(gameId, playerId, MemberFaction.PROPHETS));

        assertThat(memberships.find(UUID.randomUUID(), playerId)).isEmpty();
        assertThat(memberships.find(gameId, UUID.randomUUID())).isEmpty();
    }
}
