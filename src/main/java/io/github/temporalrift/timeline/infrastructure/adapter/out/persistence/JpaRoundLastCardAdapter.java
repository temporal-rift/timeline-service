package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.RoundLastCardPort;

/**
 * Delete-then-insert upsert: safe because {@code save} is only ever called from the single ordered
 * {@code CardPlayedAndResolutionKafkaConsumer} partition thread for a given game (design.md Decision 5) —
 * no concurrent writers for the same {@code (gameId, eraNumber, roundNumber)} key.
 */
@Repository
class JpaRoundLastCardAdapter implements RoundLastCardPort {

    private final RoundLastCardJpaRepository repository;

    JpaRoundLastCardAdapter(RoundLastCardJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, LastCard lastCard) {
        repository.deleteByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber);
        repository.save(new RoundLastCardEntity(
                new RoundKey(gameId, eraNumber, roundNumber), lastCard.effectKind(), lastCard.futureEventId()));
    }

    @Override
    public Optional<LastCard> find(UUID gameId, int eraNumber, int roundNumber) {
        return repository
                .findByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber)
                .map(e -> new LastCard(e.effectKind(), e.futureEventId()));
    }
}
