package io.github.temporalrift.timeline.domain.port.out;

/** Configured duration of a paradox's resolution phase (sagas.md Saga 5), never hard-coded in application code. */
public interface ParadoxResolutionRulesPort {

    int paradoxResolutionTimerSeconds();
}
