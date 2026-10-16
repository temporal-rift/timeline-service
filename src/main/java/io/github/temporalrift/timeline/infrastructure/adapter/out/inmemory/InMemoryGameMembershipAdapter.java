package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;

@Component
@Profile("in-memory")
class InMemoryGameMembershipAdapter implements GameMembershipPort {

    private final Table<MembershipKey, GameMembership> memberships;

    InMemoryGameMembershipAdapter(InMemoryDatabase database) {
        this.memberships = database.table(TableNames.GAME_MEMBERSHIP);
    }

    @Override
    @Transactional
    public void save(GameMembership membership) {
        memberships.put(new MembershipKey(membership.gameId(), membership.playerId()), membership);
    }

    @Override
    public Optional<GameMembership> find(UUID gameId, UUID playerId) {
        return memberships.find(new MembershipKey(gameId, playerId));
    }
}
