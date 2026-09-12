package io.github.temporalrift.timeline.domain.saga;

/** Lifecycle status of one Weaver player's chain saga. */
public enum WeaverChainSagaStatus {
    OPEN,
    COMPLETED,
    BROKEN,
    ENDED
}
