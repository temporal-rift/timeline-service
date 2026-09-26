package io.github.temporalrift.timeline.domain.event;

import java.util.UUID;

/**
 * Confirms whether a round's {@code CORRUPT} inversion of its correlated {@code PUSH}/{@code SUPPRESS}/
 * {@code SWING} actually took effect, or was voided by a same-round {@code SEAL} on the correlated card's
 * submitted target outcome. {@code targetOutcomeId} preserves that submitted coordinate as the correlated card's
 * stable identity; it does not report a destination derived later by another modifier. game-service's
 * {@code CORRUPTED_OPPONENT_CARD} scoring
 * (temporal-rift/game-service#105/#91) consumes this via {@code confirmCorruptInversion(...)}. Not event-sourced.
 */
public record CorruptInversionConfirmed(
        UUID gameId,
        int eraNumber,
        int roundNumber,
        UUID corruptingPlayerId,
        UUID targetEventId,
        UUID targetOutcomeId,
        boolean tookEffect) {}
