package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import io.github.temporalrift.timeline.domain.port.out.FutureEventEraIndexPort;

@Repository
class JpaFutureEventEraIndexAdapter implements FutureEventEraIndexPort {

    private final FutureEventEraIndexJpaRepository repository;

    JpaFutureEventEraIndexAdapter(FutureEventEraIndexJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(UUID eventId, UUID gameId, int eraNumber) {
        repository.save(new FutureEventEraIndexEntity(eventId, gameId, eraNumber));
    }

    @Override
    public List<UUID> findEventIdsByGameIdAndEraNumber(UUID gameId, int eraNumber) {
        return repository.findByGameIdAndEraNumber(gameId, eraNumber).stream()
                .map(FutureEventEraIndexEntity::eventId)
                .toList();
    }
}
