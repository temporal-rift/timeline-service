package io.github.temporalrift.timeline.domain.port.out;

/** Configured limits on Foresight writings, shared with game-service so both derive the same written outcomes. */
public interface ForesightRulesPort {

    /** How many outcomes a Prophet may write per era; a live Foresight beyond it writes nothing. At least 1. */
    int writingsPerEra();
}
