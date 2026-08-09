package io.github.temporalrift.timeline.application.saga;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.temporalrift.timeline.application.port.in.OpenParadoxResolutionPhaseUseCase;
import io.github.temporalrift.timeline.domain.event.TerminalResolution;
import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase.PendingParadox;

/**
 * Public entry point into {@code ParadoxResolutionSaga} for opening a phase, composing
 * {@link ParadoxResolutionSagaImpl} (business logic) with {@link ParadoxResolutionTimerScheduler} (in-memory timer
 * optimization) — kept as a separate class rather than folded into either so neither develops a dependency back on
 * the other (which would otherwise cycle: scheduler -> timeout processor -> saga -> scheduler).
 */
@Service
class ParadoxResolutionPhaseOpener implements OpenParadoxResolutionPhaseUseCase {

    private final ParadoxResolutionSagaImpl saga;
    private final ParadoxResolutionTimerScheduler timerScheduler;

    ParadoxResolutionPhaseOpener(ParadoxResolutionSagaImpl saga, ParadoxResolutionTimerScheduler timerScheduler) {
        this.saga = saga;
        this.timerScheduler = timerScheduler;
    }

    /**
     * Returns the phase's authoritative pending paradoxes — the caller (e.g. {@code ResolveEraCommandHandler}) must
     * publish {@code ParadoxDetected} using these ids, not the ones it originally proposed: when a phase already
     * existed for this era, the proposed ids belong to a duplicate/redelivered detection pass and will never be
     * cascaded, since the existing phase's own ids are what {@code handleTimerExpiry} eventually publishes
     * {@code ParadoxCascaded} for.
     */
    @Override
    public List<PendingParadox> open(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions) {
        var result = saga.openPhase(gameId, eraNumber, pendingParadoxes, resolvedTerminalResolutions);
        if (result.created()) {
            timerScheduler.scheduleAfterCommit(
                    result.phase().sagaId(), result.phase().timerExpiresAt());
        }
        return result.phase().pendingParadoxes();
    }
}
