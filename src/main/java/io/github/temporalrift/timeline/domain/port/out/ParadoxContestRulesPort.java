package io.github.temporalrift.timeline.domain.port.out;

/** Configured switch of how Stabilize and Detonate decide a paradox at resolution-phase close. */
public interface ParadoxContestRulesPort {

    /**
     * Whether Detonate contests Stabilize. Stabilize overrides fresh paradox detection only on an event with drawable
     * weight and at least one stabilizer; while this is on, it also needs at least as many distinct stabilizers as
     * distinct detonators. When it does not override, fresh detection decides, so the event can still clear if this
     * close's shifts removed the paradox. Off, any Stabilize on an event with drawable weight overrides. On by default.
     */
    boolean detonateContestsStabilize();
}
