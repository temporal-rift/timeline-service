package io.github.temporalrift.timeline.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;

public interface GameMembershipPortContract {

    GameMembershipPort memberships();

    @Test
    @DisplayName("memberships: a redelivered assignment overwrites the faction for the same game and player")
    default void memberships_redeliveredAssignmentConverges() {
        var gameId = UUID.randomUUID();
        var playerId = UUID.randomUUID();
        assertThat(memberships().find(gameId, playerId)).isEmpty();

        memberships().save(new GameMembership(gameId, playerId, MemberFaction.ERASERS));
        memberships().save(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));

        assertThat(memberships().find(gameId, playerId))
                .contains(new GameMembership(gameId, playerId, MemberFaction.WEAVERS));
        assertThat(memberships().find(gameId, UUID.randomUUID())).isEmpty();
        assertThat(memberships().find(UUID.randomUUID(), playerId)).isEmpty();
    }
}
