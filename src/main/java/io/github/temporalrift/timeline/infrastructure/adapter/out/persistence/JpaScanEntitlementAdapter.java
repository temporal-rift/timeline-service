package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ScanEntitlementPort;

@Repository
class JpaScanEntitlementAdapter implements ScanEntitlementPort {

    private final ScanEntitlementJpaRepository repository;

    JpaScanEntitlementAdapter(ScanEntitlementJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void upsert(UUID gameId, int eraNumber, UUID playerId, UUID eventId) {
        if (!repository.existsByGameIdAndEraNumberAndPlayerIdAndEventId(gameId, eraNumber, playerId, eventId)) {
            repository.save(new ScanEntitlementEntity(gameId, eraNumber, playerId, eventId));
        }
    }

    @Override
    public List<ScanEntitlement> findByGameAndEra(UUID gameId, int eraNumber) {
        return repository.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(e -> new ScanEntitlement(e.getPlayerId(), e.getEventId()))
                .toList();
    }

    @Override
    @Transactional
    public void deleteByGameAndEra(UUID gameId, int eraNumber) {
        repository.deleteByGameIdAndEraNumber(gameId, eraNumber);
    }

    @Override
    @Transactional
    public void deleteByGame(UUID gameId) {
        repository.deleteByGameId(gameId);
    }
}
