package io.github.temporalrift.timeline.application.saga;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.saga.ParadoxResolutionPhase;

/**
 * Database-driven safety net for paradox-resolution-phase timers, mirroring {@code ActionRoundTimerSweep}. The
 * in-memory timer scheduled when a phase opens is only a latency optimization: it dies with its instance, so this
 * sweep — which any instance can run against the shared phase table — is what guarantees a phase always
 * force-cascades, including after a crash or on an instance that never opened the phase.
 */
@Component
class ParadoxResolutionTimerSweep {

    private static final Logger log = LoggerFactory.getLogger(ParadoxResolutionTimerSweep.class);

    private final ParadoxResolutionPhaseStateManager stateManager;
    private final ParadoxResolutionTimeoutProcessor timeoutProcessor;
    private final Clock clock;

    ParadoxResolutionTimerSweep(
            ParadoxResolutionPhaseStateManager stateManager,
            ParadoxResolutionTimeoutProcessor timeoutProcessor,
            Clock clock) {
        this.stateManager = stateManager;
        this.timeoutProcessor = timeoutProcessor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${game.timers.paradox-resolution-sweep-interval}")
    void sweep() {
        stateManager.findWaitingDueBy(clock.instant()).forEach(this::process);
    }

    private void process(ParadoxResolutionPhase phase) {
        // One failing phase must not starve the rest of the sweep batch.
        try {
            timeoutProcessor.handleTimerExpiry(phase.sagaId());
        } catch (RuntimeException ex) {
            log.error(
                    "Timer sweep failed for paradox resolution phase {} (game {})", phase.sagaId(), phase.gameId(), ex);
        }
    }
}
