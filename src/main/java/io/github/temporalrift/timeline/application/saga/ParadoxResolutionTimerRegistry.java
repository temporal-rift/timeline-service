package io.github.temporalrift.timeline.application.saga;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.github.temporalrift.timeline.domain.event.ExecutionClosed;

/**
 * Tracks in-memory {@link ScheduledFuture}s keyed by {@code sagaId}, mirroring {@code game-service}'s
 * {@code ScheduledTaskRegistry}/{@code ActionRoundTimerRegistry} shape. A single {@code timeline-service}-local
 * instance is enough here — unlike {@code game-service}, this service has only one saga timer type, so there is no
 * cross-module-state concern to isolate behind a shared reusable type.
 */
@Component
class ParadoxResolutionTimerRegistry {

    private final Map<UUID, ScheduledFuture<?>> scheduledTimers = new ConcurrentHashMap<>();

    void register(UUID sagaId, ScheduledFuture<?> future) {
        if (future == null) {
            return;
        }
        var previous = scheduledTimers.put(sagaId, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * Removes the entry only if it still holds {@code expectedFuture} — a fired timer's self-cleanup must use
     * this instead of unconditional removal, or it could delete a concurrently registered replacement instead of
     * itself.
     */
    void removeIfCurrent(UUID sagaId, ScheduledFuture<?> expectedFuture) {
        scheduledTimers.remove(sagaId, expectedFuture);
    }

    /**
     * Every timer held belongs to the closed context: the close is published under the control lock, before the next
     * context can open. With logical time the timers are unarmed and never clean themselves up, so the close is the
     * only point that drops them.
     */
    @EventListener
    void onExecutionClosed(ExecutionClosed closed) {
        scheduledTimers.forEach((sagaId, future) -> {
            if (scheduledTimers.remove(sagaId, future)) {
                future.cancel(false);
            }
        });
    }

    int size() {
        return scheduledTimers.size();
    }
}
