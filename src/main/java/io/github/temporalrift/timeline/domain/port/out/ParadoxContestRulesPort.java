package io.github.temporalrift.timeline.domain.port.out;

/** Configured switch of how Stabilize and Detonate decide a paradox at resolution-phase close. */
public interface ParadoxContestRulesPort {

    /**
     * Whether Detonate contests Stabilize: an event clears only when its distinct stabilizers are at least its distinct
     * detonators. Off, any Stabilize clears it. On by default.
     */
    boolean detonateContestsStabilize();
}
