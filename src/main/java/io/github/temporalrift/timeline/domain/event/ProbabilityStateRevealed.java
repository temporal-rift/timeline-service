package io.github.temporalrift.timeline.domain.event;

import java.util.List;
import java.util.UUID;

/**
 * Private per-player reveal of one scanned {@code FutureEvent}'s current exact outcome state
 * (scan-probability-reveals capability), published at every round close for as long as the underlying SCAN
 * entitlement stays active. {@code playerId} identifies the viewer entitled to this message, not a card's
 * actor — never broadcast. Not event-sourced.
 */
public record ProbabilityStateRevealed(
        UUID gameId, int eraNumber, int roundNumber, UUID playerId, UUID eventId, List<OutcomeState> outcomes) {

    public ProbabilityStateRevealed {
        outcomes = List.copyOf(outcomes);
    }

    public record OutcomeState(UUID outcomeId, int probability, boolean isAnnihilated, boolean isSealed) {}
}
