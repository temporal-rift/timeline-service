package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface GameMembershipJpaRepository extends JpaRepository<GameMembershipEntity, UUID> {

    Optional<GameMembershipEntity> findByGameIdAndPlayerId(UUID gameId, UUID playerId);
}
