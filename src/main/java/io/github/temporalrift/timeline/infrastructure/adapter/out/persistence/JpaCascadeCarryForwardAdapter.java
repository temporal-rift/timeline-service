package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.CascadeCarryForwardPort;

@Repository
class JpaCascadeCarryForwardAdapter implements CascadeCarryForwardPort {

    private final CascadeCarryForwardJpaRepository repository;

    JpaCascadeCarryForwardAdapter(CascadeCarryForwardJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void arm(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId) {
        if (repository
                .findByGameIdAndEraNumberAndEventIdAndOutcomeId(gameId, eraNumber, eventId, outcomeId)
                .isEmpty()) {
            repository.save(new CascadeCarryForwardEntity(gameId, eraNumber, playerId, eventId, outcomeId));
        }
    }

    @Override
    public List<CascadeCarryForward> findByGameAndEra(UUID gameId, int eraNumber) {
        return repository.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(e -> new CascadeCarryForward(e.getPlayerId(), e.getEventId(), e.getOutcomeId()))
                .toList();
    }

    @Override
    @Transactional
    public void confirm(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, int targetEraNumber) {
        repository
                .findByGameIdAndEraNumberAndEventIdAndOutcomeId(gameId, eraNumber, eventId, outcomeId)
                .ifPresent(entity -> entity.setEraNumber(targetEraNumber));
    }

    @Override
    @Transactional
    public void delete(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId) {
        repository
                .findByGameIdAndEraNumberAndEventIdAndOutcomeId(gameId, eraNumber, eventId, outcomeId)
                .ifPresent(repository::delete);
    }

    @Override
    @Transactional
    public void deleteByGame(UUID gameId) {
        repository.deleteByGameId(gameId);
    }
}
