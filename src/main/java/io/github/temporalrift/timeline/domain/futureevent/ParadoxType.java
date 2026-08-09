package io.github.temporalrift.timeline.domain.futureevent;

/**
 * Mirrors {@code apis/shared-schemas/enums.yaml}'s {@code ParadoxType} by constant name so
 * {@code TimelineEventWireMapper}'s MapStruct enum mapping works with no {@code @ValueMapping}.
 * {@code CHAIN_CONFLICT} is a detection stub this slice — {@link ParadoxDetector} never reports it until the
 * Weaver chain slice supplies real chain state (GDD §6.1 Type 3).
 */
public enum ParadoxType {
    DEAD_HEAT,
    IMPOSSIBLE_ERASURE,
    CHAIN_CONFLICT,
    SEAL_BREACH
}
