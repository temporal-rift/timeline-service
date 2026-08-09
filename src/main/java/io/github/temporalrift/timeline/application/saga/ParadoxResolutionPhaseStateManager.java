package io.github.temporalrift.timeline.application.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository.CreateResult;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Component
class ParadoxResolutionPhaseStateManager {

    private final ParadoxResolutionPhaseRepository repository;

    ParadoxResolutionPhaseStateManager(ParadoxResolutionPhaseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    CreateResult createIfAbsent(ParadoxResolutionPhase candidate) {
        return repository.createIfAbsent(candidate);
    }

    @Transactional
    Optional<ParadoxResolutionPhase> findBySagaIdWithLock(UUID sagaId) {
        return repository.findBySagaIdWithLock(sagaId);
    }

    /**
     * Records {@code submission}, removing its player from the phase's pending set — a no-op if the phase is not
     * (or no longer) {@code WAITING} (already {@code CLOSING}/{@code COMPLETED}, e.g. the timer already won the
     * race), mirroring {@code ActionRoundSagaStateManager.markSubmitted}/{@code removeFromPending}.
     *
     * @return the updated phase when the submission was recorded, {@link Optional#empty()} when there is no phase
     *     for {@code (gameId, eraNumber)} or it is no longer accepting submissions
     */
    @Transactional
    Optional<ParadoxResolutionPhase> markSubmitted(UUID gameId, int eraNumber, Submission submission) {
        return repository
                .findByGameIdAndEraNumberWithLock(gameId, eraNumber)
                .filter(phase -> phase.status() == ParadoxResolutionPhaseStatus.WAITING)
                .map(phase -> repository.save(phase.withSubmission(submission)));
    }

    /**
     * Takes the phase's row lock and transitions it {@code WAITING} → {@code CLOSING} — a no-op if it is already
     * {@code CLOSING}/{@code COMPLETED}, mirroring {@code ActionRoundSagaStateManager.markClosing}. {@code CLOSING}
     * is never durably visible on its own outside this transaction: a crash mid-close rolls everything back to
     * {@code WAITING} and the loser of the race (timer sweep or a later submission) retries.
     */
    @Transactional
    void markClosing(UUID sagaId) {
        repository
                .findBySagaIdWithLock(sagaId)
                .filter(phase -> phase.status() == ParadoxResolutionPhaseStatus.WAITING)
                .ifPresent(phase -> repository.save(phase.withStatus(ParadoxResolutionPhaseStatus.CLOSING)));
    }

    @Transactional
    void markClosingByGameIdAndEraNumber(UUID gameId, int eraNumber) {
        repository
                .findByGameIdAndEraNumberWithLock(gameId, eraNumber)
                .filter(phase -> phase.status() == ParadoxResolutionPhaseStatus.WAITING)
                .ifPresent(phase -> repository.save(phase.withStatus(ParadoxResolutionPhaseStatus.CLOSING)));
    }

    @Transactional
    void complete(ParadoxResolutionPhase phase) {
        repository.save(phase.complete());
    }

    List<ParadoxResolutionPhase> findWaitingDueBy(Instant deadline) {
        return repository.findWaitingDueBy(deadline);
    }
}
