package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.membership.GameMembership;
import io.github.temporalrift.timeline.domain.membership.MemberFaction;
import io.github.temporalrift.timeline.domain.port.out.GameMembershipPort;

@Repository
class JpaGameMembershipAdapter implements GameMembershipPort {

    private final GameMembershipJpaRepository jpaRepository;

    JpaGameMembershipAdapter(GameMembershipJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void save(GameMembership membership) {
        jpaRepository
                .findByGameIdAndPlayerId(membership.gameId(), membership.playerId())
                .ifPresentOrElse(
                        entity -> entity.setFaction(membership.faction().name()),
                        () -> jpaRepository.save(new GameMembershipEntity(
                                membership.gameId(),
                                membership.playerId(),
                                membership.faction().name())));
    }

    @Override
    public Optional<GameMembership> find(UUID gameId, UUID playerId) {
        return jpaRepository
                .findByGameIdAndPlayerId(gameId, playerId)
                .map(entity -> new GameMembership(
                        entity.getGameId(), entity.getPlayerId(), MemberFaction.valueOf(entity.getFaction())));
    }
}
