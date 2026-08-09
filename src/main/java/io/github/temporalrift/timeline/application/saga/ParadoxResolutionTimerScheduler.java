package io.github.temporalrift.timeline.application.saga;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns the in-memory scheduling concerns for paradox-resolution-phase timers, mirroring
 * {@code ActionRoundTimerScheduler}: a latency optimization only — the persisted phase's {@code timerExpiresAt}
 * plus {@link ParadoxResolutionTimerSweep} are the durability guarantee (developer-notes.md §5 "Timer model").
 */
@Component
class ParadoxResolutionTimerScheduler {

    private final TaskScheduler taskScheduler;
    private final ParadoxResolutionTimeoutProcessor timeoutProcessor;
    private final ParadoxResolutionTimerRegistry timerRegistry;

    ParadoxResolutionTimerScheduler(
            @Qualifier("paradoxTaskScheduler") TaskScheduler taskScheduler,
            ParadoxResolutionTimeoutProcessor timeoutProcessor,
            ParadoxResolutionTimerRegistry timerRegistry) {
        this.taskScheduler = taskScheduler;
        this.timeoutProcessor = timeoutProcessor;
        this.timerRegistry = timerRegistry;
    }

    void scheduleAfterCommit(UUID sagaId, Instant timerExpiresAt) {
        // Starting a timer before commit would let the callback race against a phase row that is not
        // durable yet. The afterCommit hook keeps timer visibility aligned with persistent state.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reschedule(sagaId, timerExpiresAt);
                }
            });
        } else {
            reschedule(sagaId, timerExpiresAt);
        }
    }

    private void reschedule(UUID sagaId, Instant timerExpiresAt) {
        // The callback needs to identify "am I still the current timer for this sagaId" so a fire racing
        // a concurrent replacement removes only itself. It can't close over `future` directly (not yet
        // assigned when the lambda is built), so the box is set immediately after scheduling;
        // timerExpiresAt is always in the future here, which gives that assignment time to happen first.
        var selfRef = new AtomicReference<ScheduledFuture<?>>();
        var future = taskScheduler.schedule(
                () -> {
                    timerRegistry.removeIfCurrent(sagaId, selfRef.get());
                    timeoutProcessor.handleTimerExpiry(sagaId);
                },
                timerExpiresAt);
        selfRef.set(future);
        timerRegistry.register(sagaId, future);
    }
}
