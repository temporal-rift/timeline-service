package io.github.temporalrift.timeline.domain.port.out;

/** Configured switches of paradox detection. */
public interface ParadoxDetectionRulesPort {

    /** Whether an annihilated written outcome raises {@code FATE_CONTRADICTION}; on by default. */
    boolean fateContradiction();
}
