package io.github.temporalrift.timeline.application.saga;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated transactional entry point for timer callbacks, mirroring {@code ActionRoundTimeoutProcessor}: the
 * scheduler and sweep invoke this bean instead of the saga directly so timeout handling always starts in a fresh
 * transaction without relying on self-invocation through Spring proxies.
 */
@Component
class ParadoxResolutionTimeoutProcessor {

    private final ParadoxResolutionSagaImpl saga;

    ParadoxResolutionTimeoutProcessor(ParadoxResolutionSagaImpl saga) {
        this.saga = saga;
    }

    @Transactional(propagation = REQUIRES_NEW)
    void handleTimerExpiry(UUID sagaId) {
        saga.handleTimerExpiry(sagaId);
    }
}
