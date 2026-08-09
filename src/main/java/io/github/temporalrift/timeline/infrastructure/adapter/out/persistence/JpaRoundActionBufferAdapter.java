package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.RoundActionBufferPort;

@Repository
class JpaRoundActionBufferAdapter implements RoundActionBufferPort {

    private final RoundActionBufferJpaRepository repository;

    JpaRoundActionBufferAdapter(RoundActionBufferJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, BufferedAction action) {
        repository.save(new RoundActionBufferEntity(
                new RoundKey(gameId, eraNumber, roundNumber),
                action.kind(),
                action.cardType(),
                action.specialAction(),
                action.playerId(),
                action.cardInstanceId(),
                action.targetEventId(),
                action.sourceOutcomeId(),
                action.targetOutcomeId(),
                action.targetPlayerId(),
                action.occurredAt(),
                action.envelopeEventId()));
    }

    @Override
    public List<BufferedAction> findByRound(UUID gameId, int eraNumber, int roundNumber) {
        return repository.findByGameIdAndEraNumberAndRoundNumber(gameId, eraNumber, roundNumber).stream()
                .map(this::toBufferedAction)
                .toList();
    }

    private BufferedAction toBufferedAction(RoundActionBufferEntity e) {
        return new BufferedAction(
                e.kind(),
                e.cardType(),
                e.specialAction(),
                e.playerId(),
                e.cardInstanceId(),
                e.targetEventId(),
                e.sourceOutcomeId(),
                e.targetOutcomeId(),
                e.targetPlayerId(),
                e.occurredAt(),
                e.envelopeEventId());
    }
}
