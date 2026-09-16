package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port for the cross-era Weaver chain saga. */
public interface WeaverChainSagaUseCase {

    /**
     * Validates one THREAD play — a current-era source and a resolved-past target — growing the player's
     * chain or privately rejecting it.
     */
    void playThread(
            UUID gameId,
            int eraNumber,
            UUID playerId,
            UUID sourceEventId,
            UUID sourceOutcomeId,
            UUID targetEventId,
            UUID targetOutcomeId);

    /** Arms TAPESTRY protection on a 2+ link chain, once per era, or privately rejects it. */
    void playTapestry(UUID gameId, int eraNumber, UUID playerId);

    /** Discards the chain's newest link and re-anchors it to a different resolved past outcome, or rejects it. */
    void playReweave(UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId);

    /** Invalidates any chain link on the annihilated outcome, unless TAPESTRY protects it this era. */
    void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId);

    /** Ends every open chain in the game with no completion bonus. */
    void endGame(UUID gameId);
}
