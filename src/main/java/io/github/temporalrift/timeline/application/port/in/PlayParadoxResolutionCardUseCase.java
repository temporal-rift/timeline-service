package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

import io.github.temporalrift.timeline.domain.futureevent.CardGrade;

/**
 * A player's single card submission during an open paradox-resolution phase. Idempotent
 * by player within a phase — a redelivered submission for a player already recorded, or one that arrives after the
 * phase already closed, has no further effect.
 */
public interface PlayParadoxResolutionCardUseCase {

    void play(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            String cardType,
            CardGrade grade,
            UUID targetEventId,
            UUID targetOutcomeId);
}
