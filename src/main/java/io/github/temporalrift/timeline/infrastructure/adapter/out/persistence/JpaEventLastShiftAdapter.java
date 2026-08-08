package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.EventLastShiftPort;

@Repository
class JpaEventLastShiftAdapter implements EventLastShiftPort {

    private final RoundEventLastShiftJpaRepository repository;

    JpaEventLastShiftAdapter(RoundEventLastShiftJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId, EventShift shift) {
        repository.deleteByGameIdAndEraNumberAndRoundNumberAndFutureEventId(
                gameId, eraNumber, roundNumber, futureEventId);
        var snapshotEntries = shift.preShiftSnapshot().entrySet().stream()
                .map(e -> new OutcomeSnapshotTriple.OutcomeSnapshot(e.getKey(), e.getValue()))
                .toList();
        if (snapshotEntries.size() != 3) {
            throw new IllegalArgumentException(
                    "A FutureEvent snapshot must have exactly 3 outcomes, got " + snapshotEntries.size());
        }
        repository.save(new RoundEventLastShiftEntity(
                new RoundKey(gameId, eraNumber, roundNumber),
                futureEventId,
                new ShiftDescriptor(
                        shift.shiftType(), shift.sourceOutcomeId(), shift.targetOutcomeId(), shift.magnitude()),
                snapshotEntries));
    }

    @Override
    @Transactional
    public void clear(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId) {
        repository.deleteByGameIdAndEraNumberAndRoundNumberAndFutureEventId(
                gameId, eraNumber, roundNumber, futureEventId);
    }

    @Override
    public Optional<EventShift> find(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId) {
        return repository
                .findByGameIdAndEraNumberAndRoundNumberAndFutureEventId(gameId, eraNumber, roundNumber, futureEventId)
                .map(this::toEventShift);
    }

    private EventShift toEventShift(RoundEventLastShiftEntity e) {
        Map<UUID, Integer> snapshot = new LinkedHashMap<>();
        e.snapshot().forEach(outcome -> snapshot.put(outcome.outcomeId(), outcome.probability()));
        return new EventShift(e.shiftType(), e.sourceOutcomeId(), e.targetOutcomeId(), e.magnitude(), snapshot);
    }
}
