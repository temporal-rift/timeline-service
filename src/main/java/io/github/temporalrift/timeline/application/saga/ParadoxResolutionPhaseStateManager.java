package io.github.temporalrift.timeline.application.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.timeline.domain.port.out.EraPlayersPort;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository;
import io.github.temporalrift.timeline.domain.port.out.ParadoxResolutionPhaseRepository.CreateResult;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.Submission;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhaseStatus;

@Component
class ParadoxResolutionPhaseStateManager {

    private final ParadoxResolutionPhaseRepository repository;
    private final EraPlayersPort eraPlayers;

    ParadoxResolutionPhaseStateManager(ParadoxResolutionPhaseRepository repository, EraPlayersPort eraPlayers) {
        this.repository = repository;
        this.eraPlayers = eraPlayers;
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
     * race), mirroring {@code ActionRoundSagaStateManager.markSubmitted}/{@code removeFromPending}. A phase that
     * opened before its era's roster was persisted adopts it here, under the same row lock that records the
     * submission, so the all-submitted trigger becomes available again for the rest of the phase.
     *
     * @return the updated phase only when the submission was recorded — so the caller never evaluates the
     *     all-submitted trigger for a submission that changed nothing — and {@link Optional#empty()} when there is
     *     no phase for {@code (gameId, eraNumber)}, it is no longer accepting submissions, or it has no pending
     *     slot for this player (already submitted, or absent from a known roster)
     */
    @Transactional
    Optional<ParadoxResolutionPhase> markSubmitted(UUID gameId, int eraNumber, Submission submission) {
        return repository
                .findByGameIdAndEraNumberWithLock(gameId, eraNumber)
                .filter(phase -> phase.status() == ParadoxResolutionPhaseStatus.WAITING)
                .flatMap(phase -> recordSubmission(phase, submission));
    }

    /**
     * Adopts the era roster if it has landed since {@code phase} opened, then records {@code submission} against
     * the result. A just-adopted roster is persisted even when the submission itself is rejected — otherwise that
     * read is discarded and the phase stays roster-unknown, leaving a later non-roster submission to be accepted
     * on the weaker "has not submitted yet" rule.
     */
    private Optional<ParadoxResolutionPhase> recordSubmission(ParadoxResolutionPhase phase, Submission submission) {
        var adopted = adoptRosterIfNowAvailable(phase);
        if (!adopted.accepts(submission.playerId())) {
            if (adopted.rosterKnown() && !phase.rosterKnown()) {
                repository.save(adopted);
            }
            return Optional.empty();
        }
        return Optional.of(repository.save(adopted.withSubmission(submission)));
    }

    private ParadoxResolutionPhase adoptRosterIfNowAvailable(ParadoxResolutionPhase phase) {
        if (phase.rosterKnown()) {
            return phase;
        }
        return eraPlayers
                .find(phase.gameId(), phase.eraNumber())
                .map(phase::withRoster)
                .orElse(phase);
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
