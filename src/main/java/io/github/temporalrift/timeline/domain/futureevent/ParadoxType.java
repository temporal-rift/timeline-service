package io.github.temporalrift.timeline.domain.futureevent;

/**
 * Mirrors {@code apis/shared-schemas/enums.yaml}'s {@code ParadoxType} by constant name so
 * {@code TimelineEventWireMapper}'s MapStruct enum mapping works with no {@code @ValueMapping}. Only
 * {@code IMPOSSIBLE_ERASURE} is detected so far (GDD §6.1 Type 2); the remaining three constants are added when
 * their detection lands.
 */
public enum ParadoxType {
    IMPOSSIBLE_ERASURE
}
