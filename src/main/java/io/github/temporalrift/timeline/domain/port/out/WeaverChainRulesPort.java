package io.github.temporalrift.timeline.domain.port.out;

/**
 * Configured Weaver chain rules. The completion length is the same value game-service reads as the Weaver objective,
 * so a chain completes exactly when that objective is met.
 */
public interface WeaverChainRulesPort {

    /** Confirmed links at which a chain completes; at least 2. */
    int completionLength();
}
