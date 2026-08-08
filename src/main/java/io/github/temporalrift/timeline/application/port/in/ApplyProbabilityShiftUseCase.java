package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.ProbabilityShift;

/** Driving port: apply a {@code PUSH}/{@code SUPPRESS}/{@code SWING} card's effect to one {@code FutureEvent}. */
public interface ApplyProbabilityShiftUseCase {

    /**
     * {@code gameId}/{@code eraNumber}/{@code roundNumber} identify the round this shift is scoped to, for
     * AMPLIFY-doubling and last-shift tracking (design.md Decision 1/3) — not derivable from
     * {@code targetEventId} alone. {@code playerId} is the submitting player, recorded so a later
     * {@code CORRUPT} in the same round can correlate against it (faction-specials design.md Decision 4).
     */
    void apply(UUID gameId, int eraNumber, int roundNumber, UUID playerId, UUID targetEventId, ProbabilityShift shift);
}
