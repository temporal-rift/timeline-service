package io.github.temporalrift.timeline.infrastructure.adapter.out.inmemory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

/**
 * Keyed by the natural {@code (gameId, eraNumber)} key, which is also the key every lock is taken on, so the lock a
 * {@code ...WithLock} read holds is the one {@link #createIfAbsent} and {@link #save} wait on.
 */
@Component
@Profile("in-memory")
class InMemoryParadoxResolutionPhaseAdapter implements ParadoxResolutionPhaseRepository {

    private static final int SWEEP_BATCH_SIZE = 200;

    private final Table<PhaseKey, ParadoxResolutionPhase> phases;

    InMemoryParadoxResolutionPhaseAdapter(InMemoryDatabase database) {
        this.phases = database.table(TableNames.PARADOX_RESOLUTION_PHASE);
    }

    @Override
    public CreateResult createIfAbsent(ParadoxResolutionPhase candidate) {
        var key = keyOf(candidate);
        if (phases.insertIfAbsent(key, candidate)) {
            return new CreateResult(candidate, true);
        }
        var existing = phases.find(key)
                .orElseThrow(() -> new IllegalStateException("paradox_resolution_phase insert for game "
                        + candidate.gameId() + " era " + candidate.eraNumber()
                        + " conflicted but no existing row was found"));
        return new CreateResult(existing, false);
    }

    @Override
    @Transactional
    public ParadoxResolutionPhase save(ParadoxResolutionPhase phase) {
        var key = keyOf(phase);
        phases.lock(key);
        phases.find(key)
                .filter(existing -> !existing.sagaId().equals(phase.sagaId()))
                .ifPresent(existing -> {
                    throw new DuplicateKeyException("Duplicate key " + key + " in paradox_resolution_phase");
                });
        phases.put(key, phase);
        return phase;
    }

    @Override
    public Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return phases.list(phase -> phase.sagaId().equals(sagaId)).stream()
                .findFirst()
                .flatMap(phase -> phases.findForUpdate(keyOf(phase)));
    }

    @Override
    public Optional<ParadoxResolutionPhase> findByGameIdAndEraNumberWithLock(UUID gameId, int eraNumber) {
        return phases.findForUpdate(new PhaseKey(gameId, eraNumber));
    }

    @Override
    public List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline) {
        return phases.list(phase -> isWaitingDueBy(phase, deadline)).stream()
                .sorted(Comparator.comparing(ParadoxResolutionPhase::timerExpiresAt))
                .limit(SWEEP_BATCH_SIZE)
                .toList();
    }

    static boolean isWaitingDueBy(ParadoxResolutionPhase phase, Instant deadline) {
        return phase.status() == ParadoxResolutionPhaseStatus.WAITING
                && phase.timerExpiresAt() != null
                && !phase.timerExpiresAt().isAfter(deadline);
    }

    private static PhaseKey keyOf(ParadoxResolutionPhase phase) {
        return new PhaseKey(phase.gameId(), phase.eraNumber());
    }

    private record PhaseKey(UUID gameId, int eraNumber) {}
}
