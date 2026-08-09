package io.github.temporalrift.timeline.domain.futureevent;

import java.util.Map;
import java.util.UUID;

/** A probability-shifter card's effect on a {@link FutureEvent} (GDD §3 "Group 1 — Probability Shifters"). */
public sealed interface ProbabilityShift {

    /** {@code +magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Push(UUID targetOutcomeId) implements ProbabilityShift {}

    /** {@code -magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Suppress(UUID targetOutcomeId) implements ProbabilityShift {}

    /** Moves up to {@code magnitude} directly from {@code sourceOutcomeId} to {@code targetOutcomeId}. */
    record Swing(UUID sourceOutcomeId, UUID targetOutcomeId) implements ProbabilityShift {}

    /**
     * Forces {@code outcomeAId} and {@code outcomeBId} toward their combined midpoint, redistributing the
     * remainder to the third outcome (GDD §3 "Group 4 — Paradox", the mechanical inverse of {@link Swing}).
     * Unlike {@code PUSH}/{@code SUPPRESS}/{@code SWING}, has no configured magnitude of its own.
     */
    record Collide(UUID outcomeAId, UUID outcomeBId) implements ProbabilityShift {}

    /**
     * Sets each outcome's probability to an exact recorded value, bypassing magnitude/floor/ceiling — used to
     * undo a prior shift by restoring its pre-shift snapshot ({@code NULLIFY}, the undo half of {@code REDIRECT}),
     * since inverting a clamped shift arithmetically is not guaranteed to reproduce the original values exactly.
     */
    record Restore(Map<UUID, Integer> targetProbabilities) implements ProbabilityShift {}
}
