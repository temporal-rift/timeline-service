package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.temporalrift.timeline.domain.saga.WeaverChainSagaStatus;

@Repository
interface WeaverChainSagaJpaRepository extends JpaRepository<WeaverChainSagaEntity, UUID> {

    Optional<WeaverChainSagaEntity> findByChainId(UUID chainId);

    Optional<WeaverChainSagaEntity> findByGameIdAndPlayerIdAndStatus(
            UUID gameId, UUID playerId, WeaverChainSagaStatus status);

    List<WeaverChainSagaEntity> findByGameIdAndStatus(UUID gameId, WeaverChainSagaStatus status);
}
