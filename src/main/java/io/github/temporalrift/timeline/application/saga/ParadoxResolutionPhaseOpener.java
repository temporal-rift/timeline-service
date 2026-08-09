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

    @Override
    public void open(
            UUID gameId,
            int eraNumber,
            List<PendingParadox> pendingParadoxes,
            List<TerminalResolution> resolvedTerminalResolutions) {
        saga.openPhase(gameId, eraNumber, pendingParadoxes, resolvedTerminalResolutions)
                .ifPresent(result -> timerScheduler.scheduleAfterCommit(result.sagaId(), result.timerExpiresAt()));
    }
}
