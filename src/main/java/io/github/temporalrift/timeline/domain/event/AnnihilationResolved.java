package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * What one non-cancelled {@code ANNIHILATE} did to its target, read from the event's state before the round's
 * Annihilates applied: {@code erased} when the target was still eligible, {@code wasLeading} when its probability
 * equalled the highest eligible probability (ties included). game-service's {@code ANNIHILATED_OUTCOME} scoring
 * consumes it. Not event-sourced.
 */
public record AnnihilationResolved(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID annihilatingPlayerId,
        UUID targetEventId,
        UUID targetOutcomeId,
        boolean erased,
        boolean wasLeading) {}
