package io.github.temporalrift.timeline.domain.futureevent;

/**
 * Mirrors {@code apis/shared-schemas/enums.yaml}'s {@code ParadoxType} by constant name so
 * {@code TimelineEventWireMapper}'s MapStruct enum mapping works with no {@code @ValueMapping}.
 */
public enum ParadoxType {
    DEAD_HEAT,
    IMPOSSIBLE_ERASURE,
    CHAIN_CONFLICT,
    SEAL_BREACH
}
