package io.github.temporalrift.timeline.domain.futureevent;

/**
 * Mirrors {@code apis/shared-schemas/enums.yaml}'s {@code ProbabilityBand} by constant name so
 * {@code TimelineEventWireMapper}'s MapStruct enum mapping works with no {@code @ValueMapping}.
 */
public enum ProbabilityBand {
    LOW,
    MEDIUM,
    HIGH;

    /** {@code probability} at or below {@code lowMax} is LOW; at or below {@code mediumMax} is MEDIUM; else HIGH. */
    public static ProbabilityBand of(int probability, int lowMax, int mediumMax) {
        if (probability <= lowMax) {
            return LOW;
        }
        return probability <= mediumMax ? MEDIUM : HIGH;
    }
}
