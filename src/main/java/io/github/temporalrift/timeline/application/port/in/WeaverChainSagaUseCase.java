package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port for the cross-era Weaver chain saga. */
public interface WeaverChainSagaUseCase {

    /** Validates one THREAD play, growing the player's chain or privately rejecting it. */
    void playThread(UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId);

    /** Arms TAPESTRY protection on a 2+ link chain, once per era. */
    void playTapestry(UUID gameId, int eraNumber, UUID playerId);

    /** Breaks the targeted player's active chain. */
    void playUnravel(UUID gameId, int eraNumber, UUID actingPlayerId, UUID targetPlayerId);

    /** Invalidates any chain link on the annihilated outcome, unless TAPESTRY protects it. */
    void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId);

    /** Ends every open chain in the game with no completion bonus. */
    void endGame(UUID gameId);
}
