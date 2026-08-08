package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.AmplifyStatePort;

@Repository
class JpaAmplifyStateAdapter implements AmplifyStatePort {

    private final RoundAmplifyPendingJpaRepository repository;

    JpaAmplifyStateAdapter(RoundAmplifyPendingJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isPending(UUID gameId, int eraNumber, int roundNumber) {
        return repository
                .findByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber)
                .map(RoundAmplifyPendingEntity::pending)
                .orElse(false);
    }

    @Override
    @Transactional
    public void arm(UUID gameId, int eraNumber, int roundNumber) {
        repository.deleteByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber);
        repository.save(new RoundAmplifyPendingEntity(gameId, eraNumber, roundNumber, true));
    }

    @Override
    @Transactional
    public void clear(UUID gameId, int eraNumber, int roundNumber) {
        repository.deleteByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber);
    }
}
