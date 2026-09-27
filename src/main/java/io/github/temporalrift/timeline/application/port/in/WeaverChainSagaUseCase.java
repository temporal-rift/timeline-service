package io.github.temporalrift.timeline.application.port.in;

import java.util.UUID;

/** Driving port for the cross-era Weaver chain saga. */
public interface WeaverChainSagaUseCase {

    /**
     * Validates one THREAD play — a single not-yet-resolved current-era coordinate — opening a pending link on
     * the player's chain, or privately rejecting it.
     */
    void playThread(UUID gameId, int eraNumber, UUID playerId, UUID eventId, UUID outcomeId);

    /** Arms TAPESTRY protection on a 2+ link chain, once per era, or privately rejects it. */
    void playTapestry(UUID gameId, int eraNumber, UUID playerId);

    /** Moves the chain's pending link to a different live outcome of the current era; the link stays pending. */
    void playReweave(UUID gameId, int eraNumber, UUID playerId, UUID targetEventId, UUID targetOutcomeId);

    /**
     * Reacts to an outcome becoming annihilated: consumes armed Tapestry protection and clears the chain's
     * pending link without penalty when it matches, otherwise leaves it pending for paradox detection to evaluate.
     */
    void annihilateOutcome(UUID gameId, int eraNumber, UUID targetEventId, UUID targetOutcomeId);

    /**
     * Confirms or clears the game's pending chain link once its event draws a winner — the only path that
     * confirms a link: confirmed when {@code winningOutcomeId} matches the pending link's outcome, cleared (no
     * penalty) otherwise.
     */
    void resolvePendingLink(UUID gameId, int eraNumber, UUID eventId, UUID winningOutcomeId);

    /**
     * Expires the matching pending link when its event ends {@code eraNumber} without a winner — it Stalls or
     * cascades. Publishes one link invalidation without a penalty; repeated calls leave confirmed links unchanged.
     */
    void expireWinnerlessPendingLink(UUID gameId, int eraNumber, UUID eventId);

    /** Breaks the game's chain whose pending link's {@code CHAIN_CONFLICT} paradox cascaded unresolved. */
    void breakChainOnCascadedParadox(UUID gameId, int eraNumber, UUID eventId, UUID outcomeId, UUID paradoxId);

    /** Ends every open chain in the game, expiring any pending link without a completion bonus. */
    void endGame(UUID gameId);
}
