package io.github.temporalrift.timeline.infrastructure.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
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
    public void record(UUID gameId, int eraNumber, int roundNumber, UUID futureEventId, EventShift shift) {
        repository.deleteByGameIdAndEraNumberAndRoundNumberAndFutureEventId(
                gameId, eraNumber, roundNumber, futureEventId);
        var snapshotEntries = List.copyOf(shift.preShiftSnapshot().entrySet());
        if (snapshotEntries.size() != 3) {
            throw new IllegalArgumentException(
                    "A FutureEvent snapshot must have exactly 3 outcomes, got " + snapshotEntries.size());
        }
        repository.save(new RoundEventLastShiftEntity(
                gameId,
                eraNumber,
                roundNumber,
                futureEventId,
                shift.shiftType(),
                shift.sourceOutcomeId(),
                shift.targetOutcomeId(),
                shift.magnitude(),
                snapshotEntries.get(0).getKey(),
                snapshotEntries.get(0).getValue(),
                snapshotEntries.get(1).getKey(),
                snapshotEntries.get(1).getValue(),
                snapshotEntries.get(2).getKey(),
                snapshotEntries.get(2).getValue()));
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
        snapshot.put(e.snapshotOutcome1Id(), e.snapshotOutcome1Probability());
        snapshot.put(e.snapshotOutcome2Id(), e.snapshotOutcome2Probability());
        snapshot.put(e.snapshotOutcome3Id(), e.snapshotOutcome3Probability());
        return new EventShift(e.shiftType(), e.sourceOutcomeId(), e.targetOutcomeId(), e.magnitude(), snapshot);
    }
}
