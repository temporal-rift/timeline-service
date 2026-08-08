package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.PendingCorruptPort;

@Repository
class JpaPendingCorruptAdapter implements PendingCorruptPort {

    private final RoundPendingCorruptJpaRepository repository;

    JpaPendingCorruptAdapter(RoundPendingCorruptJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, UUID targetPlayerId) {
        repository.save(new RoundPendingCorruptEntity(new RoundKey(gameId, eraNumber, roundNumber), targetPlayerId));
    }

    @Override
    public List<UUID> find(UUID gameId, int eraNumber, int roundNumber) {
        return repository.findByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber).stream()
                .map(RoundPendingCorruptEntity::targetPlayerId)
                .toList();
    }
}
