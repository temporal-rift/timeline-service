package io.github.temporalrift.timeline.domain.port.out;

/**
 * Driven port for the LOW/MEDIUM/HIGH band thresholds (GDD §4.3, banded-probability-publication capability) —
 * configured, not hard-coded, mirroring {@link ProbabilityRulesPort}'s convention for the same kind of
 * provisional balance value.
 */
public interface ProbabilityBandRulesPort {

    /** Inclusive upper bound of the LOW band. */
    int bandLowMax();

    /** Inclusive upper bound of the MEDIUM band; anything above this is HIGH. */
    int bandMediumMax();
}
