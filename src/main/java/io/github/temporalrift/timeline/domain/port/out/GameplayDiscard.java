package io.github.temporalrift.timeline.domain.port.out;

/** Removes the gameplay state of a closed execution context. */
public interface GameplayDiscard {

    /**
     * Removes all gameplay state of the deployment: events, projections, sagas, timers, continuations, outbox records
     * and the claims of handled events. Only simulation control state is kept.
     */
    void discardAll();
}
