package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ScanEntitlementJpaRepository extends JpaRepository<ScanEntitlementEntity, UUID> {

    boolean existsByGameIdAndEraNumberAndPlayerIdAndEventId(UUID gameId, int eraNumber, UUID playerId, UUID eventId);

    List<ScanEntitlementEntity> findByGameIdAndEraNumber(UUID gameId, int eraNumber);

    void deleteByGameIdAndEraNumber(UUID gameId, int eraNumber);

    void deleteByGameId(UUID gameId);
}
