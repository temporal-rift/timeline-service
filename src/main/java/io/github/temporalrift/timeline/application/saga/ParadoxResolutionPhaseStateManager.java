package io.github.temporalrift.timeline.application.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Component
class ParadoxResolutionPhaseStateManager {

    private final ParadoxResolutionPhaseRepository repository;

    ParadoxResolutionPhaseStateManager(ParadoxResolutionPhaseRepository repository) {
        this.repository = repository;
    }

    /**
     * Empty when a phase already exists for {@code (gameId, eraNumber)} — the caller must not publish
     * {@code ParadoxResolutionPhaseStarted} or schedule a timer for a phase that already has one.
     */
    @Transactional
    Optional<ParadoxResolutionPhase> open(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions,
            Instant timerExpiresAt) {
        if (repository.findByGameIdAndEraNumber(gameId, eraNumber).isPresent()) {
            return Optional.empty();
        }
        var phase = new ParadoxResolutionPhase(
                UUID.randomUUID(),
                gameId,
                eraNumber,
                ParadoxResolutionPhaseStatus.WAITING,
                pendingParadoxes,
                resolvedTerminalResolutions,
                timerExpiresAt);
        return Optional.of(repository.save(phase));
    }

    @Transactional
    Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return repository.findBySagaIdWithLock(sagaId);
    }

    @Transactional
    void complete(ParadoxResolutionPhase phase) {
        repository.save(phase.complete());
    }

    List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline) {
        return repository.findWaitingDueBy(deadline);
    }
}
