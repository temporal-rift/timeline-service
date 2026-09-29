package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** A probability-shifter card's effect on a {@link FutureEvent}. */
public sealed interface ProbabilityShift {

    /** {@code +magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Push(UUID targetOutcomeId) implements ProbabilityShift {}

    /** {@code -magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Suppress(UUID targetOutcomeId) implements ProbabilityShift {}

    /** Moves up to {@code magnitude} directly from {@code sourceOutcomeId} to {@code targetOutcomeId}. */
    record Swing(UUID sourceOutcomeId, UUID targetOutcomeId) implements ProbabilityShift {}

    /**
     * Sets {@code outcomeAId} and {@code outcomeBId} to the integer floor of their combined midpoint and moves
     * any one-point remainder to the third outcome (the mechanical inverse of {@link Swing}).
     * Unlike {@code PUSH}/{@code SUPPRESS}/{@code SWING}, has no configured magnitude of its own.
     */
    record Collide(UUID outcomeAId, UUID outcomeBId) implements ProbabilityShift {}
}
