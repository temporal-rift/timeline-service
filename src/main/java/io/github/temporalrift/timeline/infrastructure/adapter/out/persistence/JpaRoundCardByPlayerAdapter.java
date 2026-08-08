package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.RoundCardByPlayerPort;

@Repository
class JpaRoundCardByPlayerAdapter implements RoundCardByPlayerPort {

    private final RoundCardByPlayerJpaRepository repository;

    JpaRoundCardByPlayerAdapter(RoundCardByPlayerJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, UUID playerId, PlayerCard card) {
        repository.deleteByGameIdAndEraNumberAndRoundNumberAndPlayerId(gameId, eraNumber, roundNumber, playerId);
        var snapshotEntries = card.preShiftSnapshot().entrySet().stream()
                .map(e -> new OutcomeSnapshotTriple.OutcomeSnapshot(e.getKey(), e.getValue()))
                .toList();
        repository.save(new RoundCardByPlayerEntity(
                new RoundKey(gameId, eraNumber, roundNumber),
                playerId,
                card.futureEventId(),
                new ShiftDescriptor(card.shiftType(), card.sourceOutcomeId(), card.targetOutcomeId(), card.magnitude()),
                snapshotEntries));
    }

    @Override
    public Optional<PlayerCard> find(UUID gameId, int eraNumber, int roundNumber, UUID playerId) {
        return repository
                .findByGameIdAndEraNumberAndRoundNumberAndPlayerId(gameId, eraNumber, roundNumber, playerId)
                .map(this::toPlayerCard);
    }

    private PlayerCard toPlayerCard(RoundCardByPlayerEntity e) {
        Map<UUID, Integer> snapshot = new LinkedHashMap<>();
        e.snapshot().forEach(outcome -> snapshot.put(outcome.outcomeId(), outcome.probability()));
        return new PlayerCard(
                e.futureEventId(), e.shiftType(), e.sourceOutcomeId(), e.targetOutcomeId(), e.magnitude(), snapshot);
    }
}
