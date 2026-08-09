package io.github.temporalrift.timeline.application.saga;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.stereotype.Component;

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
}
