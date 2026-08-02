package io.github.temporalrift.timeline.domain.futureevent;

import java.util.UUID;

/** A probability-shifter card's effect on a {@link FutureEvent} (GDD §3 "Group 1 — Probability Shifters"). */
public sealed interface ProbabilityShift {

    /** {@code +magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Push(UUID targetOutcomeId) implements ProbabilityShift {}

    /** {@code -magnitude} to {@code targetOutcomeId}, redistributed proportionally across the other two. */
    record Suppress(UUID targetOutcomeId) implements ProbabilityShift {}

    /** Moves up to {@code magnitude} directly from {@code sourceOutcomeId} to {@code targetOutcomeId}. */
    record Swing(UUID sourceOutcomeId, UUID targetOutcomeId) implements ProbabilityShift {}
}
