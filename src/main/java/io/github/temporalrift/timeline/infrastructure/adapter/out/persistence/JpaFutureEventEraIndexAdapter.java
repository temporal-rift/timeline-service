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
    public void add(UUID eventId, UUID gameId, int eraNumber, int revealIndex) {
        repository.save(new FutureEventEraIndexEntity(eventId, gameId, eraNumber, revealIndex));
    }

    @Override
    public List<IndexedEventId> findByGameIdAndEraNumber(UUID gameId, int eraNumber) {
        return repository.findByGameIdAndEraNumberOrderByRevealIndexAsc(gameId, eraNumber).stream()
                .map(entity -> new IndexedEventId(entity.eventId(), entity.revealIndex()))
                .toList();
    }
}
